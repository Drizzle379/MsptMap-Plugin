package msptmap.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字节码注入：在计时目标方法的首尾插入 {@link Bridge} 调用。
 *
 * <p>每个方法只加一个 long 局部变量保存开始时刻，不加字段、不改原有指令；未采样时开始时刻为 0，
 * 收尾调用随即返回。方块更新各入口共用嵌套层数，最外层才记账，并用 try-catch 保证异常路径也能还原。
 *
 * <p>目标类名与方法签名按 Paper 26.2（Mojang 映射）书写，版本升级后若签名变动，注入会跳过该方法
 * 并在启动日志中留痕，不影响服务器运行。
 */
public final class MsptTransformer implements ClassFileTransformer {
	private static final String BRIDGE = "msptmap/agent/Bridge";
	private static final String NMS = "msptmap/agent/Nms";

	private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
	private static final String LEVEL = "net/minecraft/world/level/Level";
	private static final String TICKING_BLOCK_ENTITY = "net/minecraft/world/level/chunk/LevelChunk$BoundTickingBlockEntity";
	private static final String SERVER_ENTITY = "net/minecraft/server/level/ServerEntity";
	private static final String TRACKED_ENTITY = "net/minecraft/server/level/ChunkMap$TrackedEntity";
	private static final String NATURAL_SPAWNER = "net/minecraft/world/level/NaturalSpawner";
	private static final String MINECRAFT_SERVER = "net/minecraft/server/MinecraftServer";
	private static final String PAPERCLIP = "io/papermc/paperclip/Paperclip";
	private static final String PAPERCLIP_HOOK = "msptmap/agent/PaperclipHook";
	private static final String URL_ARRAY = "[Ljava/net/URL;";
	private static final String SETUP_CLASSPATH_DESC = "()" + URL_ARRAY;

	private static final String DESC_BLOCK_POS = "Lnet/minecraft/core/BlockPos;";
	private static final String DESC_ENTITY = "Lnet/minecraft/world/entity/Entity;";
	private static final String DESC_BLOCK_ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";

	/**
	 * 计时目标的参数来源。
	 */
	private enum KeyKind {
		/** 方法参数是 BlockPos。 */
		POS,
		/** 方法参数是 LevelChunk。 */
		CHUNK,
		/** 方法参数是 Entity。 */
		ENTITY,
		/** 方法参数是 BlockEventData。 */
		BLOCK_EVENT,
		/** 从本对象的 blockEntity 字段取坐标。 */
		BLOCK_ENTITY_FIELD,
		/** 从本对象的 entity 字段取坐标。 */
		ENTITY_FIELD
	}

	/**
	 * 维度对象的取得方式。
	 */
	private enum LevelKind {
		/** 方法所属对象即 ServerLevel。 */
		THIS,
		/** 第一个参数是 ServerLevel。 */
		ARG0,
		/** 从 blockEntity.getLevel() 取。 */
		BLOCK_ENTITY_FIELD,
		/** 从 entity.level() 取。 */
		ENTITY_FIELD
	}

	private record SimpleTarget(String name, String desc, int category, KeyKind keyKind, int keyArg,
			LevelKind levelKind) {
		String id() {
			return name + desc;
		}
	}

	private record NeighborTarget(String name, String desc, int posArg) {
		String id() {
			return name + desc;
		}
	}

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
		try {
			return switch (className) {
				case SERVER_LEVEL -> rewriteServerLevel(loader, classfileBuffer);
				case LEVEL -> rewriteLevel(loader, classfileBuffer);
				case TICKING_BLOCK_ENTITY -> rewriteTickingBlockEntity(loader, classfileBuffer);
				case SERVER_ENTITY -> rewriteServerEntity(loader, classfileBuffer);
				case TRACKED_ENTITY -> rewriteTrackedEntity(loader, classfileBuffer);
				case NATURAL_SPAWNER -> rewriteNaturalSpawner(loader, classfileBuffer);
				case MINECRAFT_SERVER -> rewriteMinecraftServer(loader, classfileBuffer);
				case PAPERCLIP -> appendPaperclipClasspath(classfileBuffer);
				default -> null;
			};
		} catch (Throwable error) {
			// 注入失败必须放行原类，否则服务器无法启动
			Log.warn("注入 " + className + " 失败，该类将不计时：" + error);
			return null;
		}
	}

	/**
	 * 在 {@code Paperclip.setupClasspath} 的每个返回点前补一次 {@link PaperclipHook#appendSelf}，
	 * 让服务端类加载器把 agent jar 也纳入搜索路径。
	 *
	 * <p>只插入一次静态调用，栈上原为 {@code URL[]}，调用后仍是 {@code URL[]}，现有栈帧无需重算。
	 */
	private static byte[] appendPaperclipClasspath(byte[] bytes) {
		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
					String[] exceptions) {
				MethodVisitor parent = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!name.equals("setupClasspath") || !descriptor.equals(SETUP_CLASSPATH_DESC)) {
					return parent;
				}
				return new MethodVisitor(Opcodes.ASM9, parent) {
					@Override
					public void visitInsn(int opcode) {
						if (opcode == Opcodes.ARETURN) {
							super.visitMethodInsn(Opcodes.INVOKESTATIC, PAPERCLIP_HOOK, "appendSelf",
									"(" + URL_ARRAY + ")" + URL_ARRAY, false);
						}
						super.visitInsn(opcode);
					}
				};
			}
		};
		reader.accept(visitor, 0);
		return writer.toByteArray();
	}

	private static byte[] rewriteServerLevel(ClassLoader loader, byte[] bytes) {
		String owner = SERVER_LEVEL;
		return rewrite(loader, bytes, List.of(
				new SimpleTarget("tickChunk", "(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
						TickCategory.RANDOM_TICK.ordinal(), KeyKind.CHUNK, 1, LevelKind.THIS),
				new SimpleTarget("tickBlock", "(" + DESC_BLOCK_POS + "Lnet/minecraft/world/level/block/Block;)V",
						TickCategory.SCHEDULED.ordinal(), KeyKind.POS, 1, LevelKind.THIS),
				new SimpleTarget("tickFluid", "(" + DESC_BLOCK_POS + "Lnet/minecraft/world/level/material/Fluid;)V",
						TickCategory.SCHEDULED.ordinal(), KeyKind.POS, 1, LevelKind.THIS),
				new SimpleTarget("tickNonPassenger", "(" + DESC_ENTITY + ")V",
						TickCategory.ENTITY.ordinal(), KeyKind.ENTITY, 1, LevelKind.THIS),
				new SimpleTarget("doBlockEvent", "(Lnet/minecraft/world/level/BlockEventData;)Z",
						TickCategory.BLOCK_EVENT.ordinal(), KeyKind.BLOCK_EVENT, 1, LevelKind.THIS)),
				List.of(
						new NeighborTarget("updateNeighborsAt",
								"(" + DESC_BLOCK_POS + "Lnet/minecraft/world/level/block/Block;"
										+ "Lnet/minecraft/world/level/redstone/Orientation;)V", 1),
						new NeighborTarget("updateNeighborsAtExceptFromFacing",
								"(" + DESC_BLOCK_POS + "Lnet/minecraft/world/level/block/Block;"
										+ "Lnet/minecraft/core/Direction;"
										+ "Lnet/minecraft/world/level/redstone/Orientation;)V", 1),
						new NeighborTarget("neighborChanged",
								"(" + DESC_BLOCK_POS + "Lnet/minecraft/world/level/block/Block;"
										+ "Lnet/minecraft/world/level/redstone/Orientation;)V", 1),
						new NeighborTarget("neighborChanged",
								"(Lnet/minecraft/world/level/block/state/BlockState;" + DESC_BLOCK_POS
										+ "Lnet/minecraft/world/level/block/Block;"
										+ "Lnet/minecraft/world/level/redstone/Orientation;Z)V", 2)),
				owner);
	}

	private static byte[] rewriteLevel(ClassLoader loader, byte[] bytes) {
		return rewrite(loader, bytes, List.of(), List.of(
				new NeighborTarget("neighborShapeChanged",
						"(Lnet/minecraft/core/Direction;" + DESC_BLOCK_POS + DESC_BLOCK_POS
								+ "Lnet/minecraft/world/level/block/state/BlockState;II)V", 2)),
				LEVEL);
	}

	private static byte[] rewriteTickingBlockEntity(ClassLoader loader, byte[] bytes) {
		return rewrite(loader, bytes, List.of(
				new SimpleTarget("tick", "()V", TickCategory.BLOCK_ENTITY.ordinal(),
						KeyKind.BLOCK_ENTITY_FIELD, -1, LevelKind.BLOCK_ENTITY_FIELD)),
				List.of(), TICKING_BLOCK_ENTITY);
	}

	private static byte[] rewriteServerEntity(ClassLoader loader, byte[] bytes) {
		return rewrite(loader, bytes, List.of(
				new SimpleTarget("sendChanges", "()V", TickCategory.ENTITY.ordinal(),
						KeyKind.ENTITY_FIELD, -1, LevelKind.ENTITY_FIELD)),
				List.of(), SERVER_ENTITY);
	}

	private static byte[] rewriteTrackedEntity(ClassLoader loader, byte[] bytes) {
		return rewrite(loader, bytes, List.of(
				new SimpleTarget("updatePlayers", "(Ljava/util/List;)V", TickCategory.ENTITY.ordinal(),
						KeyKind.ENTITY_FIELD, -1, LevelKind.ENTITY_FIELD)),
				List.of(), TRACKED_ENTITY);
	}

	private static byte[] rewriteNaturalSpawner(ClassLoader loader, byte[] bytes) {
		return rewrite(loader, bytes, List.of(
				new SimpleTarget("spawnForChunk",
						"(Lnet/minecraft/server/level/ServerLevel;"
								+ "Lnet/minecraft/world/level/chunk/LevelChunk;"
								+ "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V",
						TickCategory.SPAWN.ordinal(), KeyKind.CHUNK, 1, LevelKind.ARG0)),
				List.of(), NATURAL_SPAWNER);
	}

	private static byte[] rewriteMinecraftServer(ClassLoader loader, byte[] bytes) {
		return rewrite(loader, bytes, List.of(), List.of(), MINECRAFT_SERVER, "tickServer",
				"(Ljava/util/function/BooleanSupplier;)V");
	}

	private static byte[] rewrite(ClassLoader loader, byte[] bytes, List<SimpleTarget> simple,
			List<NeighborTarget> neighbors, String owner) {
		return rewrite(loader, bytes, simple, neighbors, owner, null, null);
	}

	/**
	 * 执行一次改写：先把简单计时与方块更新目标按方法签名建表，再逐方法包装。
	 *
	 * @param tickMethod 非 null 时只在该方法首尾插入 tick 起止
	 */
	private static byte[] rewrite(ClassLoader loader, byte[] bytes, List<SimpleTarget> simple,
			List<NeighborTarget> neighbors, String owner, String tickMethod, String tickDesc) {
		ClassReader reader = new ClassReader(bytes);
		Map<String, Integer> locals = maxLocals(reader);
		Map<String, SimpleTarget> simpleTargets = new HashMap<>();
		for (SimpleTarget target : simple) {
			simpleTargets.put(target.id(), target);
		}
		Map<String, NeighborTarget> neighborTargets = new HashMap<>();
		for (NeighborTarget target : neighbors) {
			neighborTargets.put(target.id(), target);
		}
		HierarchyWriter writer = new HierarchyWriter(reader, loader);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
					String[] exceptions) {
				MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (visitor == null) {
					return null;
				}
				String id = name + descriptor;
				int startLocal = locals.getOrDefault(id, argumentSlots(access, descriptor));
				SimpleTarget target = simpleTargets.get(id);
				if (target != null) {
					return simple(visitor, owner, target, startLocal);
				}
				NeighborTarget neighbor = neighborTargets.get(id);
				if (neighbor != null) {
					return neighbor(visitor, owner, neighbor, startLocal);
				}
				if (tickMethod != null && tickMethod.equals(name) && tickDesc.equals(descriptor)) {
					return ticks(visitor);
				}
				return visitor;
			}
		}, 0);
		return writer.toByteArray();
	}

	/**
	 * 逐方法读取原文件中的 maxLocals，供新增局部变量从其后开始使用。
	 */
	private static Map<String, Integer> maxLocals(ClassReader reader) {
		Map<String, Integer> locals = new HashMap<>();
		reader.accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
					String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMaxs(int maxStack, int maxLocals) {
						locals.put(name + descriptor, maxLocals);
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return locals;
	}

	/**
	 * 没有 maxLocals 记录时的兜底：参数槽位数。
	 */
	private static int argumentSlots(int access, String descriptor) {
		int slots = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type type : Type.getArgumentTypes(descriptor)) {
			slots += type.getSize();
		}
		return slots;
	}

	/**
	 * 简单计时：首部记开始，每个返回指令前入账。
	 */
	private static MethodVisitor simple(MethodVisitor visitor, String owner, SimpleTarget target, int startLocal) {
		return new MethodVisitor(Opcodes.ASM9, visitor) {
			@Override
			public void visitCode() {
				super.visitCode();
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "begin", "()J", false);
				visitor.visitVarInsn(Opcodes.LSTORE, startLocal);
			}

			@Override
			public void visitInsn(int opcode) {
				if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
					Label skip = new Label();
					visitor.visitVarInsn(Opcodes.LLOAD, startLocal);
					visitor.visitInsn(Opcodes.LCONST_0);
					visitor.visitInsn(Opcodes.LCMP);
					visitor.visitJumpInsn(Opcodes.IFEQ, skip);
					pushInt(visitor, target.category());
					pushLevel(visitor, owner, target.levelKind());
					pushKey(visitor, owner, target.keyKind(), target.keyArg());
					visitor.visitVarInsn(Opcodes.LLOAD, startLocal);
					visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "end",
							"(ILjava/lang/Object;JJ)V", false);
					visitor.visitLabel(skip);
				}
				super.visitInsn(opcode);
			}
		};
	}

	/**
	 * 方块更新：首部进入一层，每个返回指令前退出一层；异常路径由 catch-all 处理器收尾后原样抛出。
	 */
	private static MethodVisitor neighbor(MethodVisitor visitor, String owner, NeighborTarget target, int startLocal) {
		Label tryStart = new Label();
		Label tryEnd = new Label();
		Label handler = new Label();
		int exceptionLocal = startLocal + 2;
		return new MethodVisitor(Opcodes.ASM9, visitor) {
			@Override
			public void visitCode() {
				super.visitCode();
				visitor.visitVarInsn(Opcodes.ALOAD, 0);
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "neighborBegin",
						"(Ljava/lang/Object;)J", false);
				visitor.visitVarInsn(Opcodes.LSTORE, startLocal);
				visitor.visitTryCatchBlock(tryStart, tryEnd, handler, null);
				visitor.visitLabel(tryStart);
			}

			@Override
			public void visitInsn(int opcode) {
				if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
					pushNeighborEnd(visitor, owner, target, startLocal);
				}
				super.visitInsn(opcode);
			}

			@Override
			public void visitMaxs(int maxStack, int maxLocals) {
				visitor.visitLabel(tryEnd);
				visitor.visitLabel(handler);
				visitor.visitVarInsn(Opcodes.ASTORE, exceptionLocal);
				pushNeighborEnd(visitor, owner, target, startLocal);
				visitor.visitVarInsn(Opcodes.ALOAD, exceptionLocal);
				visitor.visitInsn(Opcodes.ATHROW);
				super.visitMaxs(maxStack, maxLocals);
			}
		};
	}

	/**
	 * 服务端 tick 的起止。
	 */
	private static MethodVisitor ticks(MethodVisitor visitor) {
		return new MethodVisitor(Opcodes.ASM9, visitor) {
			@Override
			public void visitCode() {
				super.visitCode();
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "tickStart", "()V", false);
			}

			@Override
			public void visitInsn(int opcode) {
				if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
					visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "tickEnd", "()V", false);
				}
				super.visitInsn(opcode);
			}
		};
	}

	/** 压入一个小常量；耗时类别的序号在 0..6。 */
	private static void pushInt(MethodVisitor visitor, int value) {
		if (value >= -1 && value <= 5) {
			visitor.visitInsn(Opcodes.ICONST_0 + value);
		} else {
			visitor.visitIntInsn(Opcodes.BIPUSH, value);
		}
	}

	private static void pushNeighborEnd(MethodVisitor visitor, String owner, NeighborTarget target, int startLocal) {
		visitor.visitVarInsn(Opcodes.ALOAD, 0);
		pushKey(visitor, owner, KeyKind.POS, target.posArg());
		visitor.visitVarInsn(Opcodes.LLOAD, startLocal);
		visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "neighborEnd",
				"(Ljava/lang/Object;JJ)V", false);
	}

	private static void pushLevel(MethodVisitor visitor, String owner, LevelKind kind) {
		switch (kind) {
			case THIS, ARG0 -> visitor.visitVarInsn(Opcodes.ALOAD, 0);
			case BLOCK_ENTITY_FIELD -> {
				visitor.visitVarInsn(Opcodes.ALOAD, 0);
				visitor.visitFieldInsn(Opcodes.GETFIELD, owner, "blockEntity", DESC_BLOCK_ENTITY);
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, NMS, "blockEntityLevel",
						"(Ljava/lang/Object;)Ljava/lang/Object;", false);
			}
			case ENTITY_FIELD -> {
				visitor.visitVarInsn(Opcodes.ALOAD, 0);
				visitor.visitFieldInsn(Opcodes.GETFIELD, owner, "entity", DESC_ENTITY);
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, NMS, "entityLevel",
						"(Ljava/lang/Object;)Ljava/lang/Object;", false);
			}
		}
	}

	private static void pushKey(MethodVisitor visitor, String owner, KeyKind kind, int arg) {
		switch (kind) {
			case POS, CHUNK, ENTITY, BLOCK_EVENT -> {
				visitor.visitVarInsn(Opcodes.ALOAD, arg);
				String method = switch (kind) {
					case POS -> "keyPos";
					case CHUNK -> "keyChunk";
					case ENTITY -> "keyEntity";
					default -> "keyBlockEvent";
				};
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, NMS, method,
						"(Ljava/lang/Object;)J", false);
			}
			case BLOCK_ENTITY_FIELD -> {
				visitor.visitVarInsn(Opcodes.ALOAD, 0);
				visitor.visitFieldInsn(Opcodes.GETFIELD, owner, "blockEntity", DESC_BLOCK_ENTITY);
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, NMS, "keyBlockEntity",
						"(Ljava/lang/Object;)J", false);
			}
			case ENTITY_FIELD -> {
				visitor.visitVarInsn(Opcodes.ALOAD, 0);
				visitor.visitFieldInsn(Opcodes.GETFIELD, owner, "entity", DESC_ENTITY);
				visitor.visitMethodInsn(Opcodes.INVOKESTATIC, NMS, "keyEntity",
						"(Ljava/lang/Object;)J", false);
			}
		}
	}

	/**
	 * 计算公共父类时不加载目标类（避免与类定义过程循环），而是直接读取类文件头。
	 */
	private static final class HierarchyWriter extends ClassWriter {
		private static final String OBJECT = "java/lang/Object";

		private final ClassLoader loader;
		private final Map<String, Info> infos = new HashMap<>();

		HierarchyWriter(ClassReader reader, ClassLoader loader) {
			super(reader, COMPUTE_FRAMES | COMPUTE_MAXS);
			this.loader = loader;
			infos.put(reader.getClassName(),
					new Info((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0,
							reader.getSuperName(), reader.getInterfaces()));
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			if (type1.equals(type2)) {
				return type1;
			}
			Info first = info(type1);
			Info second = info(type2);
			// 信息不足时退到 Object：可能损失精度，但不会产生错误类型
			if (first == null || second == null) {
				return OBJECT;
			}
			if (first.isInterface() || second.isInterface()) {
				return OBJECT;
			}
			if (isAssignable(type1, type2)) {
				return type2;
			}
			if (isAssignable(type2, type1)) {
				return type1;
			}
			String candidate = first.superName();
			while (candidate != null) {
				if (isAssignable(type2, candidate)) {
					return candidate;
				}
				Info info = info(candidate);
				candidate = info == null ? null : info.superName();
			}
			return OBJECT;
		}

		private boolean isAssignable(String from, String to) {
			return isAssignable(from, to, new HashSet<>());
		}

		private boolean isAssignable(String from, String to, Set<String> seen) {
			if (from == null) {
				return false;
			}
			if (from.equals(to)) {
				return true;
			}
			if (!seen.add(from)) {
				return false;
			}
			Info info = info(from);
			if (info == null) {
				return false;
			}
			for (String implemented : info.interfaces()) {
				if (isAssignable(implemented, to, seen)) {
					return true;
				}
			}
			return isAssignable(info.superName(), to, seen);
		}

		private Info info(String name) {
			if (infos.containsKey(name)) {
				return infos.get(name);
			}
			Info info = read(name);
			infos.put(name, info);
			return info;
		}

		private Info read(String name) {
			try (InputStream in = resource(name)) {
				if (in == null) {
					return null;
				}
				ClassReader reader = new ClassReader(in);
				return new Info((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0,
						reader.getSuperName(), reader.getInterfaces());
			} catch (IOException | RuntimeException error) {
				return null;
			}
		}

		private InputStream resource(String name) {
			String file = name + ".class";
			ClassLoader current = loader;
			return current != null ? current.getResourceAsStream(file)
					: ClassLoader.getSystemResourceAsStream(file);
		}
	}

	private record Info(boolean isInterface, String superName, String[] interfaces) {
	}
}

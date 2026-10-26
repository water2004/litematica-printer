package me.aleksilassila.litematica.printer.printer.placement;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.properties.*;

enum BlockKind {
    // 放置
    TORCH(
            BaseTorchBlock.class
    ),                                      // 火把
    SLAB(SlabBlock.class),                  // 台阶
    STAIR(StairBlock.class),                // 楼梯
    TRAPDOOR(TrapDoorBlock.class),          // 活板门
    STRIP_LOG(RotatedPillarBlock.class),    // 去皮原木
    ANVIL(AnvilBlock.class),                // 铁砧
    HOPPER(HopperBlock.class),              // 漏斗
    CAMPFIRE(CampfireBlock.class),          // 营火
    BED(BedBlock.class),                    // 床
    BELL(BellBlock.class),                  // 钟
    AMETHYST(AmethystClusterBlock.class),   // 紫水晶
    DOOR(DoorBlock.class),                  // 门
    COCOA(CocoaBlock.class),                // 可可豆
    CRAFTER(CrafterBlock.class),            // 合成器
    CHEST(ChestBlock.class),                // 箱子
    OBSERVER(ObserverBlock.class),          // 侦测器
    LADDER(LadderBlock.class),              // 梯子
    LANTERN(LanternBlock.class),            // 灯笼
    ROD(RodBlock.class),                    // 末地烛 避雷针
    TRIPWIRE_HOOK(TripWireHookBlock.class), // 绊线钩
    RAIL(BaseRailBlock.class),              // 铁轨
    PISTON(PistonBaseBlock.class),          // 活塞 （为了避免被破坏错误状态破坏）
    SIGN(
            StandingSignBlock.class,
            WallSignBlock.class
            , WallHangingSignBlock.class
            , CeilingHangingSignBlock.class
    ),
    BANNER(AbstractBannerBlock.class),      // 旗帜
    SKULL(AbstractSkullBlock.class),        // 头颅
    CROPS(AttachedStemBlock.class, StemBlock.class, CropBlock.class, BeetrootBlock.class),          // 农作物(茎)

    // 点击
    FLOWER_POT(FlowerPotBlock.class),               // 花盆
    BIG_DRIPLEAF_STEM(BigDripleafStemBlock.class),  // 大垂叶茎
    CAVE_VINES(CaveVinesBlock.class, CaveVinesPlantBlock.class),                // 洞穴藤蔓
    WEEPING_VINES(WeepingVinesBlock.class, WeepingVinesPlantBlock.class),       // 垂泪藤
    TWISTING_VINES(TwistingVinesBlock.class, TwistingVinesPlantBlock.class),    // 缠怨藤
    SNOW(SnowLayerBlock.class),                     // 雪
    CANDLES(CandleBlock.class),                     // 蜡烛
    REPEATER(RepeaterBlock.class),                  // 中继器
    COMPARATOR(ComparatorBlock.class),              // 比较器
    PICKLES(SeaPickleBlock.class),                  // 海泡菜
    NOTE_BLOCK(NoteBlock.class),                    // 音符盒
    END_PORTAL_FRAME(EndPortalFrameBlock.class),    // 末地传送门框架
    FLOWERBED(
            FlowerBedBlock.class
    ), // 花簇（ojng你看看你这是什么抽象命名）
    VINES(VineBlock.class),                         // 藤蔓
    GLOW_LICHEN(GlowLichenBlock.class),             // 发光地衣
    FIRE(FireBlock.class, SoulFireBlock.class),     // 火，灵魂火
    RED_STONE_WIRE(RedstoneWireBlock.class),       // 红石粉
    FENCE_GATE(FenceGateBlock.class),               // 栅栏门
    LEVER(LeverBlock.class),                        // 拉杆
    CAULDRON(CauldronBlock.class, LavaCauldronBlock.class, LayeredCauldronBlock.class), // 炼药锅
    DAYLIGHT_DETECTOR(DaylightDetectorBlock.class), // 阳光探测器
    COMPOSTER(ComposterBlock.class),                // 堆肥桶

    // 其他
    FARMLAND(FarmlandBlock.class),              // 耕地
    DIRT_PATH(PathBlock.class),             // 土径
    NETHER_PORTAL(NetherPortalBlock.class), // 下界传送门
    SKIP(SkullBlock.class, LiquidBlock.class, BubbleColumnBlock.class, LilyPadBlock.class), // 跳过
    DEFAULT; // 默认

    private final Class<?>[] classes;

    BlockKind(Class<?>... classes) {
        this.classes = classes;
    }

    /**
     * 方块处理器只取决于运行时类型。ClassValue 既能支持模组方块子类，
     * 又避免扫描时为每个坐标重复遍历整张 BlockKind 类型表。
     */
    private static final BlockKind[] KINDS = BlockKind.values();
    private static final ClassValue<BlockKind> BY_CLASS = new ClassValue<>() {
        @Override
        protected BlockKind computeValue(Class<?> blockClass) {
            for (BlockKind hook : KINDS) {
                if (hook == BlockKind.DEFAULT) continue;
                for (Class<?> candidate : hook.classes) {
                    if (candidate != null && candidate.isAssignableFrom(blockClass)) {
                        return hook;
                    }
                }
            }
            return BlockKind.DEFAULT;
        }
    };
    boolean containsExactClass(Class<?> blockClass) {
        for (Class<?> candidate : classes) {
            if (candidate == blockClass) return true;
        }
        return false;
    }

    static BlockKind forBlockClass(Class<?> blockClass) {
        return BY_CLASS.get(blockClass);
    }
}

package me.aleksilassila.litematica.printer.handler;

import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * 事务签名：决定哪些方块可以在同一事务里连续执行而不需切换物品或改变行为。
 *
 * <p>同桶内的方块保证：同类 action、相同主物品、相同特殊路径。
 * 消费时整桶连续处理，减少物品切换与重复判定。</p>
 */
public record TransactionKey(Category category, @Nullable Item primaryItem) {
    /** 基类默认：所有位置同质，Fill/FluidRemoval/Bedrock 等无需分组时使用。 */
    public static final TransactionKey HOMOGENEOUS = new TransactionKey(Category.HOMOGENEOUS, null);

    public enum Category {
        /** 基类默认同质桶 */
        HOMOGENEOUS,
        /** Print 普通放置 */
        PLACE,
        /** Print 点击放置 */
        CLICK,
        /** Print 连锁破坏 */
        CHAIN_BREAK,
        /** Print 破冰等水 */
        ICE_WATER
    }
}

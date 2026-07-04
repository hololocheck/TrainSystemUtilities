package com.trainsystemutilities.electrification;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/**
 * FE 電化システム用カスタム damage type の {@link ResourceKey} 定義。
 *
 * <p>実体の登録は {@code data/trainsystemutilities/damage_type/<id>.json} で行う。
 */
public final class ModDamageTypes {
    private ModDamageTypes() {}

    /** 通電中の架線に近づいた時の誘導感電。 */
    public static final ResourceKey<DamageType> INDUCTION = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(TrainSystemUtilities.MOD_ID, "induction"));
}

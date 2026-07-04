package com.trainsystemutilities.blockentity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 路線記号データ（JR駅ナンバリング風）。
 * 円形バッジにアルファベット2文字 + 数字2桁 + 路線カラー。
 * ベクター描画で拡大縮小しても崩れない。
 */
public class LineSymbol {

    private UUID id;
    private String letters;      // アルファベット2文字 (例: "JY")
    private int number;          // 駅番号 0-99
    private String borderColor;  // 縁色 hex (例: "#9acd32")
    private String name;         // 記号の名前 (例: "山手線")
    private int borderRadius;    // ふちのアール 5-25 (デフォルト20=正円)

    public LineSymbol(String letters, int number, String borderColor, String name, int borderRadius) {
        this.id = UUID.randomUUID();
        this.letters = letters != null ? letters.toUpperCase() : "JA";
        this.number = Math.max(0, Math.min(99, number));
        this.borderColor = borderColor != null ? borderColor : "#4fc3f7";
        this.name = name != null ? name : "";
        this.borderRadius = Math.max(5, Math.min(25, borderRadius));
    }

    public LineSymbol(String letters, int number, String borderColor, String name) {
        this(letters, number, borderColor, name, 12);
    }

    // Getters
    public UUID getId() { return id; }
    public String getLetters() { return letters; }
    public int getNumber() { return number; }
    public String getBorderColor() { return borderColor; }
    public String getName() { return name; }
    public String getNumberStr() { return String.format("%02d", number); }
    public int getBorderRadius() { return borderRadius; }

    // Setters
    public void setLetters(String letters) { this.letters = letters != null ? letters.toUpperCase() : "JA"; }
    public void setNumber(int number) { this.number = Math.max(0, Math.min(99, number)); }
    public void setBorderColor(String color) { this.borderColor = color != null ? color : "#4fc3f7"; }
    public void setName(String name) { this.name = name != null ? name : ""; }
    public void setBorderRadius(int r) { this.borderRadius = Math.max(5, Math.min(25, r)); }

    // NBT
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Letters", letters);
        tag.putInt("Number", number);
        tag.putString("BorderColor", borderColor);
        tag.putString("Name", name);
        tag.putInt("BorderRadius", borderRadius);
        return tag;
    }

    public static LineSymbol load(CompoundTag tag) {
        try {
            var sym = new LineSymbol(
                    tag.getString("Letters"),
                    tag.getInt("Number"),
                    tag.getString("BorderColor"),
                    tag.getString("Name"),
                    tag.contains("BorderRadius") ? tag.getInt("BorderRadius") : 12
            );
            if (tag.hasUUID("Id")) sym.id = tag.getUUID("Id");
            return sym;
        } catch (Exception e) {
            com.trainsystemutilities.TrainSystemUtilities.LOGGER.debug("[LineSymbol] NBT load failed", e);
            return null;
        }
    }

    public static ListTag saveList(List<LineSymbol> symbols) {
        ListTag list = new ListTag();
        for (var s : symbols) list.add(s.save());
        return list;
    }

    public static List<LineSymbol> loadList(ListTag list) {
        List<LineSymbol> symbols = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            var s = load(list.getCompound(i));
            if (s != null) symbols.add(s);
        }
        return symbols;
    }
}

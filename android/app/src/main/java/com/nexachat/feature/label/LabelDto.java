package com.nexachat.feature.label;

import androidx.annotation.Nullable;

/** Moshi response bean for the private labels API. */
public final class LabelDto {
    @Nullable private String id;
    @Nullable private String name;
    @Nullable private String colorId;

    public LabelDto() {}

    public LabelDto(String id, String name, String colorId) {
        this.id = id;
        this.name = name;
        this.colorId = colorId;
    }

    @Nullable public String getId() { return id; }
    @Nullable public String getName() { return name; }
    @Nullable public String getColorId() { return colorId; }

    public ConversationLabel toLabel() {
        if (id == null || name == null) {
            throw new IllegalStateException("Label response omitted required fields");
        }
        return new ConversationLabel(id, name, LabelColor.fromId(colorId), false);
    }
}

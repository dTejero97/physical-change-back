package com.physicalchange.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Elemento de una colección. itemId es polimórfico según itemType (IDEA → content_idea; HOOK/COPY → content_snippet). */
@Entity
@Table(name = "CONTENT_COLLECTION_ITEM")
@IdClass(ContentCollectionItem.Key.class)
public class ContentCollectionItem {
    @Id @Column(name = "COLLECTION_ID") private Long collectionId;
    @Id @Column(name = "ITEM_TYPE", length = 5) private String itemType;
    @Id @Column(name = "ITEM_ID") private Long itemId;
    @Column(name = "ADDED_AT", nullable = false) private Instant addedAt = Instant.now();

    protected ContentCollectionItem() {}
    public ContentCollectionItem(Long collectionId, String itemType, Long itemId) {
        this.collectionId = collectionId; this.itemType = itemType; this.itemId = itemId;
    }

    public Long getCollectionId() { return collectionId; }
    public String getItemType() { return itemType; }
    public Long getItemId() { return itemId; }
    public Instant getAddedAt() { return addedAt; }

    public static class Key implements Serializable {
        private Long collectionId;
        private String itemType;
        private Long itemId;
        public Key() {}
        public Key(Long c, String t, Long i) { collectionId = c; itemType = t; itemId = i; }
        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(collectionId, k.collectionId) && Objects.equals(itemType, k.itemType)
                    && Objects.equals(itemId, k.itemId);
        }
        @Override public int hashCode() { return Objects.hash(collectionId, itemType, itemId); }
    }
}

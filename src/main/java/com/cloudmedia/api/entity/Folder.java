package com.cloudmedia.api.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity
@Table(name = "Folders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Folder {
    @Id
    @Column(columnDefinition = "VARCHAR(36)")
    private String id;

    @Column(nullable = false)
    private String folderName;

    @Column(columnDefinition = "VARCHAR(36)")
    private String parentFolderId; // Trỏ về Id của bảng này (NULL nếu ở gốc)

    @Column(columnDefinition = "VARCHAR(36)")
    private String userId; // Trỏ về bảng Users

    // Getters và Setters...
}
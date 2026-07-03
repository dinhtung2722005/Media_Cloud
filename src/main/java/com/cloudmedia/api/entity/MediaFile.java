package com.cloudmedia.api.entity;
import jakarta.persistence.*;
import lombok.*;
@Entity
@Table(name = "MediaFiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MediaFile {
    @Id
    @Column(columnDefinition = "VARCHAR(36)")
    private String id; // Mã định danh duy nhất của file (GUID vật lý)

    @Column(nullable = false)
    private String title;

    @Column(length = 500, nullable = false)
    private String storagePath;

    @Column(length = 10, nullable = false)
    private String extension;

    @Column(nullable = false)
    private Long fileSize; // Dung lượng file tính bằng Byte

    @Column(columnDefinition = "VARCHAR(36)")
    private String folderId; // Trỏ về bảng Folders (NULL nếu ở gốc)

    @Column(columnDefinition = "VARCHAR(36)")
    private String userId; // Trỏ về bảng Users

    @Column(length = 20, nullable = false)
    private String status; // Uploading, Processing, Ready, Failed

    @Column(length = 20, nullable = false)
    private String source; // Local hoặc YouTube

    // Getters và Setters...
}
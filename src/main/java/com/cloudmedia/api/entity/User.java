package com.cloudmedia.api.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
@Entity
@Table(name = "Users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    @Column(columnDefinition = "VARCHAR(36)")
    private String id; // Sinh tự động UUID

    @Column(length = 50, unique = true, nullable = false)
    private String username;

    @Column(length = 100, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

}
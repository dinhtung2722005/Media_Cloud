package com.cloudmedia.api.entity;
import jakarta.persistence.*;
import java.util.Date;

@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 1000, nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private Date expirationDate;

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public Date getExpirationDate() {
        return expirationDate;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void setExpirationDate(Date expirationDate) {
        this.expirationDate = expirationDate;
    }

    public RevokedToken(Long id, String token, Date expirationDate) {
        this.id = id;
        this.token = token;
        this.expirationDate = expirationDate;
    } 
    public RevokedToken() {
    } 
}
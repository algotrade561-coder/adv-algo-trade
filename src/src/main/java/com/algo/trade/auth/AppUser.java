package com.algo.trade.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    private String name;

    @Column(nullable = false)
    private String role = "USER";

    private boolean enabled = true;

    private Instant lastLoginAt;
    private Instant createdAt;

    @Column(length = 2048)
    private String pictureUrl;

    @PrePersist
    protected void onCreate() { if (createdAt == null) createdAt = Instant.now(); }

    protected AppUser() {}

    public AppUser(String email, String role) {
        this.email = email;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public String getPictureUrl() { return pictureUrl; }
    public void setPictureUrl(String pictureUrl) {
        this.pictureUrl = pictureUrl != null && pictureUrl.length() > 2048 ? pictureUrl.substring(0, 2048) : pictureUrl;
    }
}

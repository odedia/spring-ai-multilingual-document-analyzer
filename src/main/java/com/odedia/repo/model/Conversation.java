package com.odedia.repo.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation")
public class Conversation {
    @Id
    private UUID id;
    
	private String title;
    private Instant createdAt;
    private Instant lastActive;
    private String owner;

    public Conversation() {
		super();
	}

    public Conversation(UUID id, String title, Instant createdAt, Instant lastActive) {
		super();
		this.id = id;
		this.title = title;
		this.createdAt = createdAt;
		this.lastActive = lastActive;
	}
	public UUID getId() {
		return id;
	}
	public void setId(UUID id) {
		this.id = id;
	}
	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public Instant getCreatedAt() {
		return createdAt;
	}
	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
	public Instant getLastActive() {
		return lastActive;
	}
	public void setLastActive(Instant lastActive) {
		this.lastActive = lastActive;
	}
    public String getOwner() {
        return owner;
    }
    public void setOwner(String owner) {
        this.owner = owner;
    }
}

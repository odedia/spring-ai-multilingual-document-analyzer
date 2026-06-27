package com.odedia.repo.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Records which chat model produced each assistant answer in a conversation, so the
 * "answered by" badge survives a page refresh (chat-memory messages don't store the model).
 * {@code seq} is the 0-based ordinal of the assistant answer within the conversation.
 */
@Entity
@Table(name = "answer_model", indexes = {
        @Index(name = "idx_answermodel_conversation", columnList = "conversationId,seq")
})
public class AnswerModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String conversationId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private Instant createdAt;

    public AnswerModel() {
    }

    public AnswerModel(String conversationId, int seq, String model) {
        this.conversationId = conversationId;
        this.seq = seq;
        this.model = model;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

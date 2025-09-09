package com.odedia.repo.jpa;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.odedia.repo.model.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findAllByOrderByLastActiveDesc();
    List<Conversation> findByOwnerOrderByLastActiveDesc(String owner);
}

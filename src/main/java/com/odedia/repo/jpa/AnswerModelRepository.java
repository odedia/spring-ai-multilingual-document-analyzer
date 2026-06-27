package com.odedia.repo.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.odedia.repo.model.AnswerModel;

public interface AnswerModelRepository extends JpaRepository<AnswerModel, String> {

    List<AnswerModel> findByConversationIdOrderBySeqAsc(String conversationId);

    Optional<AnswerModel> findByConversationIdAndSeq(String conversationId, int seq);

    void deleteByConversationId(String conversationId);
}

package com.odedia.repo.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.odedia.repo.model.DocumentFigure;

public interface DocumentFigureRepository extends JpaRepository<DocumentFigure, UUID> {

	Optional<DocumentFigure> findFirstByFilenameAndPageNumberAndKindOrderByFigureIndexDesc(
			String filename, int pageNumber, String kind);

	List<DocumentFigureView> findByFilenameAndPageNumberOrderByFigureIndexAsc(String filename, int pageNumber);

	List<DocumentFigureView> findByFilenameOrderByPageNumberAscFigureIndexAsc(String filename);

	List<DocumentFigureView> findByIdIn(java.util.Collection<UUID> ids);

	long countByFilename(String filename);

	@Transactional
	void deleteByFilename(String filename);
}

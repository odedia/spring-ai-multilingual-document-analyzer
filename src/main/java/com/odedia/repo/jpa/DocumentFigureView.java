package com.odedia.repo.jpa;

import java.util.UUID;

/** Metadata-only view so listing figures does not load JPEG bytes. */
public interface DocumentFigureView {

	UUID getId();

	String getFilename();

	int getPageNumber();

	String getKind();

	String getTitle();

	String getCaption();
}

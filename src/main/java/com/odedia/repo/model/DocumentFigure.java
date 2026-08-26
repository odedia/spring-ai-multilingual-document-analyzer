package com.odedia.repo.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "document_figure", indexes = {
		@Index(name = "idx_document_figure_file_page", columnList = "filename, page_number")
})
public class DocumentFigure {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String filename;

	@Column(name = "page_number", nullable = false)
	private int pageNumber;

	@Column(name = "figure_index", nullable = false)
	private int figureIndex;

	@Column(nullable = false, length = 40)
	private String kind;

	@Column(length = 500)
	private String title;

	@Column(columnDefinition = "text")
	private String caption;

	@Column(name = "readable_text", columnDefinition = "text")
	private String readableText;

	@Column(name = "mime_type", nullable = false, length = 80)
	private String mimeType;

	private int width;

	private int height;

	@Column(name = "image_data", nullable = false, columnDefinition = "bytea")
	private byte[] imageData;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public DocumentFigure() {
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public int getPageNumber() {
		return pageNumber;
	}

	public void setPageNumber(int pageNumber) {
		this.pageNumber = pageNumber;
	}

	public int getFigureIndex() {
		return figureIndex;
	}

	public void setFigureIndex(int figureIndex) {
		this.figureIndex = figureIndex;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getCaption() {
		return caption;
	}

	public void setCaption(String caption) {
		this.caption = caption;
	}

	public String getReadableText() {
		return readableText;
	}

	public void setReadableText(String readableText) {
		this.readableText = readableText;
	}

	public String getMimeType() {
		return mimeType;
	}

	public void setMimeType(String mimeType) {
		this.mimeType = mimeType;
	}

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public byte[] getImageData() {
		return imageData;
	}

	public void setImageData(byte[] imageData) {
		this.imageData = imageData;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}

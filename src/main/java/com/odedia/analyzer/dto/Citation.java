package com.odedia.analyzer.dto;

import java.util.Objects;

public record Citation(String filename, int page) {

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Citation other)) {
			return false;
		}
		return page == other.page && Objects.equals(filename, other.filename);
	}

	@Override
	public int hashCode() {
		return Objects.hash(filename, page);
	}
}

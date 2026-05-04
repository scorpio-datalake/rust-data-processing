//! Small helpers for **prompt-sized** text derived from structured reports (Phase 2 optional).

/// Truncate a UTF-8 string to at most `max_bytes` **UTF-8 bytes**, never splitting a codepoint.
/// If truncated, appends an ASCII ellipsis marker and a short suffix explaining truncation.
pub fn truncate_utf8_by_bytes(input: &str, max_bytes: usize) -> String {
    if input.len() <= max_bytes {
        return input.to_string();
    }
    let mut end = max_bytes;
    while end > 0 && !input.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}… [truncated from {} bytes]", &input[..end], input.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn truncate_respects_char_boundary() {
        let s = "ééé"; // 6 bytes
        let t = truncate_utf8_by_bytes(s, 3);
        assert!(t.starts_with('é') || t.contains('…'));
        assert!(t.len() < s.len() + 40);
    }
}

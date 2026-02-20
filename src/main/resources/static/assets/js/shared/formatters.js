export function escapeHtml(value) {
    if (value == null) {
        return "";
    }
    return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/\"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

export function shortText(value, max = 96) {
    if (!value) {
        return "";
    }
    return value.length > max ? `${value.slice(0, max)}...` : value;
}

export function timeAgo(timestamp) {
    const deltaSec = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
    if (deltaSec < 60) {
        return `${deltaSec}s ago`;
    }
    const min = Math.floor(deltaSec / 60);
    if (min < 60) {
        return `${min} min ago`;
    }
    return `${Math.floor(min / 60)}h ago`;
}

export function formatClock(timestamp) {
    return new Date(timestamp).toLocaleTimeString([], {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hour12: false
    });
}

export interface SseMessage {
  event: string;
  data: string;
}

/**
 * Incremental parser for text/event-stream. Feed it chunks as they arrive; it returns the messages
 * completed by each chunk (a message ends at a blank line).
 */
export class SseParser {
  private buffer = "";
  private event = "message";
  private data: string[] = [];

  push(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const lines = this.buffer.split(/\r?\n/);
    this.buffer = lines.pop() ?? "";
    const messages: SseMessage[] = [];
    for (const line of lines) {
      if (line === "") {
        if (this.data.length > 0) messages.push({ event: this.event, data: this.data.join("\n") });
        this.event = "message";
        this.data = [];
      } else if (line.startsWith(":")) {
        // comment / keep-alive
      } else {
        const colon = line.indexOf(":");
        const field = colon === -1 ? line : line.slice(0, colon);
        const value = colon === -1 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (field === "event") this.event = value;
        else if (field === "data") this.data.push(value);
      }
    }
    return messages;
  }
}

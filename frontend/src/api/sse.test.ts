import { SseParser } from "./sse";

describe("SseParser", () => {
  it("parses events split across chunks", () => {
    const parser = new SseParser();

    expect(parser.push("event:snapshot\ndata:{\"a\":")).toEqual([]);
    expect(parser.push("1}\n\nevent: update\ndata: x\n")).toEqual([{ event: "snapshot", data: '{"a":1}' }]);
    expect(parser.push("\n")).toEqual([{ event: "update", data: "x" }]);
  });

  it("joins multi-line data, skips comments, defaults the event name", () => {
    const parser = new SseParser();

    expect(parser.push(": keep-alive\r\ndata:a\r\ndata:b\r\n\r\n")).toEqual([{ event: "message", data: "a\nb" }]);
  });
});

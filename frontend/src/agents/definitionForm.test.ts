import { defaultPermission, emptyForm, fromForm, toForm, toolKey } from "./definitionForm";

describe("definition form", () => {
  it("round-trips a definition", () => {
    const definition = {
      description: "Keeps notes",
      systemPrompt: "Be careful.",
      model: "qwen2.5:32b",
      temperature: 0.2,
      toolCalling: "JSON" as const,
      maxSteps: 5,
      outputSchema: { type: "object" },
      tools: [{ server: "notes", tool: "save_note", permission: "APPROVAL" as const, labels: ["PII", "EXT"] }],
    };

    const form = toForm(definition);
    expect(form.tools[toolKey("notes", "save_note")].labels).toBe("PII, EXT");
    expect(fromForm(form)).toEqual({ definition });
  });

  it("turns blank optional fields into nulls", () => {
    const result = fromForm({ ...emptyForm(), systemPrompt: "x" });

    expect(result).toEqual({
      definition: {
        description: null,
        systemPrompt: "x",
        model: null,
        temperature: null,
        toolCalling: null,
        maxSteps: 8,
        outputSchema: null,
        tools: [],
      },
    });
  });

  it.each([
    [{ systemPrompt: " " }, "시스템 프롬프트를 입력하세요."],
    [{ temperature: "3" }, "temperature는 0 ~ 2 사이여야 합니다."],
    [{ maxSteps: "0" }, "최대 단계는 1 ~ 50 사이의 정수여야 합니다."],
    [{ maxSteps: "2.5" }, "최대 단계는 1 ~ 50 사이의 정수여야 합니다."],
    [{ outputSchema: "{oops" }, "출력 스키마가 올바른 JSON이 아닙니다."],
    [{ outputSchema: "[1]" }, "출력 스키마는 JSON 객체여야 합니다."],
  ])("rejects %o", (patch, error) => {
    expect(fromForm({ ...emptyForm(), systemPrompt: "x", ...patch })).toEqual({ error });
  });

  it("starts read-only tools as automatic and others as needing approval", () => {
    expect(defaultPermission({ readOnlyHint: true })).toBe("AUTO");
    expect(defaultPermission({ destructiveHint: true })).toBe("APPROVAL");
    expect(defaultPermission(null)).toBe("APPROVAL");
  });
});

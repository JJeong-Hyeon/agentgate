/** The arguments of a call waiting for approval, in full (secrets are masked by the runtime). */
export function PayloadPreview({ text }: { text: string }) {
  return (
    <details className="payload-preview">
      <summary>인자 전체 보기</summary>
      <pre>{text}</pre>
    </details>
  );
}

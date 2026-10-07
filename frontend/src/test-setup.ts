import "@testing-library/jest-dom/vitest";

// jsdom lacks the layout APIs React Flow measures nodes with.
class ResizeObserverStub {
  private readonly callback: ResizeObserverCallback;
  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
  }
  observe(target: Element) {
    const contentRect = { width: 1000, height: 600, x: 0, y: 0, top: 0, left: 0, right: 1000, bottom: 600 };
    this.callback([{ target, contentRect } as unknown as ResizeObserverEntry], this as unknown as ResizeObserver);
  }
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

class DOMMatrixReadOnlyStub {
  m22: number;
  constructor(transform?: string) {
    const scale = transform?.match(/scale\(([0-9.]+)\)/)?.[1];
    this.m22 = scale !== undefined ? Number(scale) : 1;
  }
}
globalThis.DOMMatrixReadOnly ??= DOMMatrixReadOnlyStub as unknown as typeof DOMMatrixReadOnly;

Object.defineProperties(HTMLElement.prototype, {
  offsetHeight: { get: () => 40, configurable: true },
  offsetWidth: { get: () => 140, configurable: true },
});
(SVGElement.prototype as unknown as { getBBox: () => DOMRect }).getBBox = () =>
  ({ x: 0, y: 0, width: 0, height: 0 }) as DOMRect;

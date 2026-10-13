/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react()],
  server: {
    // "localhost" can resolve to ::1 only, which breaks 127.0.0.1 access.
    // Set VITE_HOST=0.0.0.0 to expose the dev server on the LAN.
    host: process.env.VITE_HOST ?? "127.0.0.1",
    // Same-origin calls to AgentGate in development; no CORS setup needed.
    proxy: { "/api": process.env.AGENTGATE_URL ?? "http://localhost:8080" },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
  },
});

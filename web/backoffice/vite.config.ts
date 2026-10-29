import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Em desenvolvimento, o Vite serve a tela na :5174 e repassa ao gateway (BFF) o que é dele.
// Sem changeOrigin: o gateway vê "localhost:5174" e monta o endereço de volta do login para cá
// (registrado no cliente do Keycloak). Construída, a tela é servida pelo próprio gateway, na :8090.
const GATEWAY = process.env.GATEWAY_URL ?? "http://localhost:8090";
const repassar = { target: GATEWAY, changeOrigin: false };

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: { "/api": repassar, "/oauth2": repassar, "/login": repassar, "/logout": repassar },
  },
  build: { outDir: "dist", sourcemap: false },
  test: { environment: "jsdom", setupFiles: ["./src/teste/preparar.ts"] },
});

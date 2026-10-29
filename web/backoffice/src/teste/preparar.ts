import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Sem "globals" no Vitest, a Testing Library não desmonta a tela sozinha entre um teste e outro.
afterEach(cleanup);

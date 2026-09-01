import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";

/* Fluent styles itself through Griffel at runtime, so there is no stylesheet to import. What is
   left for the page is the two things a document has to say for itself: fill the viewport, and
   let the browser paint form controls and scrollbars for the right scheme. */
const base = document.createElement("style");
base.textContent = `
  html, body, #root { height: 100%; margin: 0; }
  body { overflow: hidden; }
`;
document.head.appendChild(base);

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

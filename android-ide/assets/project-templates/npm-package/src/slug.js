import { customAlphabet } from "nanoid";

const createId = customAlphabet("0123456789abcdefghijklmnopqrstuvwxyz", 10);

export function toSlug(value) {
  return String(value)
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

export function createIdentifier(prefix = "item") {
  return `${toSlug(prefix) || "item"}_${createId()}`;
}

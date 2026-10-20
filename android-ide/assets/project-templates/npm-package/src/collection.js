export class Collection {
  #items = new Map();

  add(item) {
    if (!item?.id) throw new Error("Collection items require an id");
    this.#items.set(item.id, item);
    return item;
  }

  get(id) {
    return this.#items.get(id);
  }

  remove(id) {
    return this.#items.delete(id);
  }

  values() {
    return [...this.#items.values()];
  }

  get size() {
    return this.#items.size;
  }
}

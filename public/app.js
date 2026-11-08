const list = document.getElementById("notes");
const form = document.getElementById("note-form");
const input = document.getElementById("note-text");

async function loadNotes() {
    const response = await fetch("/api/notes");
    const notes = await response.json();
    list.replaceChildren(...notes.map(renderNote));
}

function renderNote(note) {
    const item = document.createElement("li");
    item.textContent = note.text;
    const remove = document.createElement("button");
    remove.textContent = "Sil";
    remove.addEventListener("click", async () => {
        await fetch(`/api/notes/${note.id}`, { method: "DELETE" });
        loadNotes();
    });
    item.append(remove);
    return item;
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    await fetch("/api/notes", { method: "POST", body: input.value });
    input.value = "";
    loadNotes();
});

loadNotes();

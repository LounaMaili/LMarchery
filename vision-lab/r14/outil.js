"use strict";

// Le lecteur partagé R5/R8 fournit le canevas, les tubes, les repères et l’annulation.
const negativeSegments = [];
const ignoreRegions = [];
const drawBase = refresh;
refresh = function () {
  drawBase();
  context.lineWidth = Math.max(2, canvas.width / 360);
  context.strokeStyle = "#ff654e";
  for (const record of negativeSegments) {
    context.beginPath();
    context.moveTo(...record.axis[0]);
    context.lineTo(...record.axis[1]);
    context.stroke();
  }
  context.strokeStyle = "#c369e5";
  for (const [[x, y], [right, bottom]] of ignoreRegions) {
    context.strokeRect(x, y, right - x, bottom - y);
  }
};

byId("load").onclick = async () => {
  if (packet) {
    status("Ouvrez un autre onglet pour commencer une nouvelle photo.");
    return;
  }
  const button = byId("load");
  button.disabled = true;
  try {
    const photo = byId("photo").files[0];
    if (!photo) throw new Error("Choisissez une photo JPEG ou PNG.");
    const hash = await digest(photo);
    image = await createImageBitmap(photo, {imageOrientation: "from-image"});
    const id = "review-" + BigInt("0x" + hash.slice(0, 16)).toString();
    packet = {schema: 1, id, original_sha256: hash, photo_sha256: hash, size: [image.width, image.height]};
    canvas.width = image.width;
    canvas.height = image.height;
    canvas.hidden = false;
    byId("controls").disabled = false;
    byId("photo").disabled = true;
    refresh();
    status("Photo prête. Choisissez un type de tracé.");
  } catch (error) {
    status(error.message);
    button.disabled = false;
  }
};

for (const kind of ["negative", "ignore"]) {
  byId(kind).onclick = () => {
    action = {kind, points: [], reason: byId("negativeKind").value};
    refresh();
    status(kind === "ignore" ? "Cliquez sur deux coins opposés de la zone." :
      "Cliquez sur les deux extrémités du segment parasite vérifié.");
  };
}

const clickBase = canvas.onclick;
canvas.onclick = (event) => {
  if (!["negative", "ignore"].includes(action?.kind)) {
    clickBase(event);
    return;
  }
  const xy = [Math.floor(event.offsetX * canvas.width / canvas.clientWidth),
    Math.floor(event.offsetY * canvas.height / canvas.clientHeight)];
  if (xy.some((v, i) => v < 0 || v >= packet.size[i])) return;
  if (!action.points.length) {
    action.points.push(xy);
    refresh();
    status("Cliquez sur le second point.");
    return;
  }
  const [x,y] = action.points[0];
  if (action.kind === "negative") {
    if (Math.hypot(x-xy[0], y-xy[1]) < 4) {
      status("Choisissez un segment d’au moins quatre pixels.");
      return;
    }
    negativeSegments.push({axis: [[x,y],xy], reason: action.reason});
    history.push(negativeSegments);
  } else {
    if (x === xy[0] || y === xy[1]) {
      status("La zone doit couvrir une surface.");
      return;
    }
    ignoreRegions.push([[Math.min(x,xy[0]),Math.min(y,xy[1])], [Math.max(x,xy[0]),Math.max(y,xy[1])]]);
    history.push(ignoreRegions);
  }
  action = null;
  refresh();
  status("Tracé enregistré dans ce relevé.");
};

byId("export").onclick = () => {
  const meta = {};
  for (const id of ["reader", "source", "session", "board"]) {
    meta[id] = byId(id).value.trim();
    if (!meta[id]) {
      byId(id).focus();
      status("Renseignez le lecteur, la provenance, la séance et le support avant l’export.");
      return;
    }
  }
  if (action) {
    status("Terminez ou annulez le tracé en cours.");
    return;
  }
  const complete = byId("complete").checked;
  const record = {...packet, annotated_by: meta.reader, complete, arrows, landmarks,
    corpus_r14: {version: 1, date_utc: new Date().toISOString(), source: meta.source,
      session: meta.session, board: meta.board, split: byId("split").value, rights: "local_only",
      worn: byId("worn").checked, grouped: byId("grouped").checked,
      empty: complete && !arrows.length && !ignoreRegions.length,
      negative_segments: negativeSegments, ignore_regions: ignoreRegions}};
  const url = URL.createObjectURL(new Blob([JSON.stringify(record,null,2)], {type:"application/json"}));
  const link = document.createElement("a");
  link.href = url;
  link.download = packet.id + "-annotation.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url),1000);
  status("Relevé JSON exporté. Conservez-le avec la photo d’origine.");
};

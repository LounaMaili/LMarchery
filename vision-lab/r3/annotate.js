"use strict";

const byId = (id) => document.getElementById(id);
const canvas = byId("canvas");
const context = canvas.getContext("2d");
let packet, image, action;
let nextArrow = 1;
const arrows = [];
const landmarks = [];
const history = [];
const status = (message) => { byId("status").textContent = message; };

async function digest(file) {
  const hash = await crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return Array.from(new Uint8Array(hash), (item) => item.toString(16).padStart(2, "0")).join("");
}

byId("load").onclick = async () => {
  if (packet) {
    status("Ouvrez cette page dans un nouvel onglet pour annoter une autre image.");
    return;
  }
  try {
    const data = JSON.parse(await byId("packet").files[0].text());
    const photo = byId("photo").files[0];
    if (data.schema !== 1 || !/^review-\d+$/.test(data.id) || !/^[a-f0-9]{64}$/.test(data.original_sha256)
        || data.photo_sha256 !== await digest(photo)) {
      throw new Error("La photo ne correspond pas à la fiche.");
    }
    const bitmap = await createImageBitmap(photo);
    if (bitmap.width !== data.size[0] || bitmap.height !== data.size[1]) {
      bitmap.close();
      throw new Error("Dimensions différentes de la fiche.");
    }
    packet = data;
    image = bitmap;
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    canvas.hidden = false;
    byId("controls").disabled = false;
    refresh();
    status(`Photo ${data.id} prête.`);
  } catch (error) {
    status(error.message);
  }
};

byId("shaft").onclick = () => {
  const visibility = byId("visibility").value;
  const uncertainty = byId("uncertainty").valueAsNumber;
  if (visibility === "visible" && !(uncertainty >= 0)) {
    status("Indiquez une incertitude de position valide.");
    return;
  }
  action = {kind: "shaft", points: [], visibility, uncertainty, tail: byId("tail").value,
    group: byId("group").value.trim() || null, near: byId("boundary").checked};
  status(visibility === "visible" ? "Cliquez sur l’extrémité visible la plus proche du contact."
    : "Cliquez sur une extrémité du segment visible.");
};

byId("geometry").onclick = () => {
  const name = byId("landmark").value.trim();
  if (!name || landmarks.some((landmark) => landmark.id === name)) {
    status("Indiquez un nom de repère encore inutilisé.");
    return;
  }
  action = {kind: "geometry", name, points: []};
  status("Cliquez sur le repère visible.");
};

canvas.onclick = (event) => {
  if (!action) return;
  const box = canvas.getBoundingClientRect();
  const point = [Math.floor((event.offsetX / canvas.clientWidth) * canvas.width),
    Math.floor((event.offsetY / canvas.clientHeight) * canvas.height)];
  if (box.width <= 0 || point.some((v, index) => v < 0 || v >= packet.size[index])) return;
  if (action.kind === "geometry") {
    landmarks.push({id: action.name, point});
    history.push(landmarks);
  } else {
    action.points.push(point);
    if (action.points.length === 1) {
      status("Cliquez sur l’autre extrémité visible du tube.");
      refresh();
      return;
    }
    if (action.points.length === 2) {
      if (Math.hypot(point[0] - action.points[0][0], point[1] - action.points[0][1]) < 4) {
        action.points.pop();
        status("Choisissez une extrémité plus éloignée.");
        return;
      }
      if (action.visibility === "visible") {
        status("Cliquez sur le point de contact avec le papier.");
        refresh();
        return;
      }
    }
    const [start, tail, entry] = action.points;
    if (entry && entry[0] === tail[0] && entry[1] === tail[1]) {
      action.points.pop();
      status("Le contact et l’extrémité opposée doivent être distincts.");
      return;
    }
    arrows.push({id: `tube-${nextArrow++}`, axis: [start,tail], tail, entry: entry ?? null,
      visibility: action.visibility, orientation: entry ? "entry-to-opposite" : "unresolved",
      uncertainty_px: entry ? action.uncertainty : null,
      near_boundary: entry ? action.near : null, state: "standalone", group: action.group,
      opposite_kind: action.tail});
    history.push(arrows);
  }
  action = null;
  status(`${arrows.length} tube(s), ${landmarks.length} repère(s).`);
  refresh();
};

function refresh() {
  context.drawImage(image, 0, 0);
  context.strokeStyle = "#31f3f0";
  context.lineWidth = Math.max(2, canvas.width/320);
  for (const mark of arrows) {
    context.beginPath();
    context.moveTo(...mark.axis[0]);
    context.lineTo(...mark.axis[1]);
    context.stroke();
    if (mark.entry) dot(mark.entry, "#fff347");
  }
  for (const mark of landmarks) dot(mark.point, "#de90ff");
  for (const point of action?.points ?? []) dot(point, "#31f3f0");
}

function dot(point, color) {
  context.fillStyle = color;
  context.beginPath();
  context.arc(...point, Math.max(3, canvas.width/205), 0, Math.PI*2);
  context.fill();
}

byId("undo").onclick = () => {
  if (action) action = null;
  else history.pop()?.pop();
  status(`${arrows.length} tube(s), ${landmarks.length} repère(s).`);
  refresh();
};

byId("export").onclick = () => {
  const reader = byId("reader").value.trim();
  if (!reader || action) {
    status("Indiquez le lecteur et terminez ou annulez le tracé en cours.");
    return;
  }
  const record = {...packet, annotated_by: reader, complete: byId("complete").checked, arrows, landmarks};
  const url = URL.createObjectURL(new Blob([JSON.stringify(record, null, 2)], {type: "application/json"}));
  const link = document.createElement("a");
  link.href = url;
  link.download = `${packet.id}-annotation.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

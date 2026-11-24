"""Indexer les planches GitHub par source et créditer chaque dérivé."""
import argparse
from pathlib import Path
from r14.mesurer import read


def render(folder):
    p=read(folder/'index.json')
    close={r['cas']:r for r in read(folder/'details.json')}
    rows=['# Planches de relecture R17','',
          'Les 28 planches JPEG sont directement consultables sur GitHub. Chaque image montre le contexte, le détail brut, le ou les axes nommés, le segment de sortie, les régions ignorées et entrées connues, puis leur superposition. Le cadre et les hachures jaunes désignent une région ignorée. Les petits points magenta marquent les véritables extrémités du segment. Les valeurs du vérificateur sont absentes.',
          '', 'La [visionneuse hors ligne](../visionneuse.html) permet aussi de choisir chaque calque séparément dans le contexte et le détail. Ouvrir le fichier HTML depuis une copie du dépôt ou la capsule, en gardant le dossier figures voisin. Elle démarre sur les photographies seules. [Coordonnées, crédits et empreintes individuels](index.json) · [Coordonnées des loupes](details.json) · [Reproduction](../reproduire.md)',
          '', '[Bilan graphique de la revue et de l’inventaire](bilan.png) · [Données de revue](../bilan.json) · [Effectifs par instance](../effectifs-instances.json)',
          '', '## Photographies et droits','',
          'Les planches et loupes sont des adaptations des photographies et de la trame vidéo citées ci-dessous. Réduction, recadrage, agrandissement et ajout des calques par LMArchery pour R17. Chaque dérivé photographique est distribué sous la licence de sa propre source, y compris les adaptations sous partage à l’identique. Les repères orange des deux loupes de correction sont les annotations historiques et les cyan les propositions distinctes de R17.']
    for k in dict.fromkeys(a['photo'] for a in p):
        group=[a for a in p if a['photo']==k];s=group[0]['droits']
        rows+=['','### '+s['credit']+' · '+k,'',f"{s['titre']}. Crédit {s['credit']}. [Notice source]({s['url']}) · [{s['nom_licence']}]({s['licence']})",'']
        for a in group:
            key=a['cas'];ids=', '.join(r['instance'] for r in a['references'])
            line=f"- [{key} · {ids}]({a['fichier']})"
            if key in close:line+=f" · [Loupe de détail]({close[key]['fichier']})"
            rows.append(line)
    (folder/'README.md').write_text('\n'.join(rows)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('folder',type=Path)
    render(p.parse_args().folder)

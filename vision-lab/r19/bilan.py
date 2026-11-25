"""Recalculer les résultats à partir des sorties archivées, sans modèle ni image de développement."""
import argparse
from pathlib import Path

from r19.donnees import ROOT, SEEDS, archive, read, save, verify
from r19.mesures import baseline, candidate, decision, dev_refs, summarize


def replay(archives,out):
    refs = dev_refs(verify())
    (out/'mesures').mkdir(parents=True,exist_ok=True)
    all_reports = {}
    for system in ['B', *map(str,SEEDS)]:
        predictions = baseline(refs) if system=='B' else candidate(refs,archives/system)
        report, curve = summarize(refs,predictions)
        archive(out/'mesures'/(system+'.json'),report)
        archive(out/'mesures'/('courbe-'+system+'.json'),curve)
        all_reports[system] = report
    result = decision(all_reports)
    save(out/'decision.json',result)
    return all_reports,result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out',type=Path)
    parser.add_argument('--archives',type=Path,default=ROOT/'predictions')
    args = parser.parse_args()
    replay(args.archives,args.out)

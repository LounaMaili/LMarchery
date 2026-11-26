"""Sceller les choix, les contrôles et le code avant les trois trajectoires candidates."""
from pathlib import Path
from r19.donnees import fingerprint, read, save

if __name__=='__main__':
    root=Path('r24')
    old=read('r22/configuration.json')
    parity=read(root/'comparabilite.json')
    config={k:old[k] for k in ('graines','pas','decision_pas','initial_R9','etats_initiaux_par_graine',
            'photos','taille_lot','cycles','expositions','seuil_presence','requêtes','poids_pertes',
            'optimiseur','ecretage','torch_threads','interop','opencv_threads','blas_threads','plans',
            'coefficient_dice','presence','affectation','seuil_masque','axe_sondes_min','axe_sondes_total',
            'entree_px704_max','seuils_score_R19')}
    files=[*sorted(root.glob('*.py')), root/'protocole.md',root/'comparabilite.json',root/'reorientation.md',
           *sorted((root/'diagnostic').glob('*.json')),Path('test_r24_cpu.py'),Path('test_r24.py'),
           Path('../.github/workflows/vision-lab.yml')]
    config.update(base_R23='44d4e2d4476a8fba0605a114f2f8a4fb601dc16c',
              motif='52165a77b4ea386444e6e327d54507df2e0f5fec',temoin='r22/archives/dice',
              secondaire='r21/archives/temoin', noms_dimensions_et_effectifs=parity['groupes'],
              taux_par_groupe=dict(tete=.001,bloc8=.0001), total_parametres=205212,
              trainables_par_groupe={g:sum(p['elements'] for p in ps.values()) for g,ps in parity['groupes'].items()},
              budget=dict(ajustements=3,mises_a_jour=2304,evaluations_train=54,lectures_dev_test=0),
              criteres=dict(gains_hors_Crigges=2,provenances_hors_Crigges=2,pertes_triplets_R22_max=0,
                            pertes_union_entrees_R21_R22_max=0,entrees_union=26,graines_min=2),
              empreintes_gel={str(p):fingerprint(p) for p in files})
    save(root/'configuration.json',config)

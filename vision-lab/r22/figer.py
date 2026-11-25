"""Consigner les paramètres et les empreintes des fichiers du gel R22."""
from pathlib import Path
from r19.donnees import fingerprint, read, save

if __name__=='__main__':
    root=Path('r22')
    old=read('r21/configuration.json')
    files=[*sorted(root.glob('*.py')),root/'protocole.md',root/'comparabilite.json',
           *sorted((root/'diagnostic').glob('*.json')), Path('test_r22.py'),Path('test_r22_cpu.py')]
    config={k:old[k] for k in ('graines','pas','decision_pas','initial_R9','etats_initiaux_par_graine',
           'photos','taille_lot','cycles','expositions','seuil_presence','requêtes','parametres',
           'poids_pertes','optimiseur','ecretage','torch_threads','interop','opencv_threads','blas_threads')}
    config.update(reference='r21/archives/temoin',plans='r21/tirages.json',coefficient_dice=1.,
        presence='historique R19',affectation='hongroise R19 courante, sans terme Dice',seuil_masque=.9,
        axe_sondes_min=11,axe_sondes_total=21,entree_px704_max=10,
        seuils_score_R19={s:p['seuil_R19'] for s,p in old['poids_temoin_historiques'].items()},
        ajustements=3,budget_lectures_train=54,photos_dev_test=0,
        critere=dict(gains_triplets_hors_Crigges_min=2,provenances_des_gains_min=2,
                     pertes_triplets_max=0,pertes_entrees_couvertes_max=0,graines_min=2),
        empreintes_gel={str(p):fingerprint(p) for p in files})
    save(root/'configuration.json',config)

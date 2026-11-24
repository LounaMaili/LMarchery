"""Ajuster une fois la régression sur les seules lignes scellées ordinary-9301."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import subprocess
import warnings

import numpy as np
import scipy
import sklearn
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler


def fit(protocol, output):
    inputs=Path('r16/exemples-fit.json')
    for name in (inputs,Path('r16/protocole.md'),Path('r16/revue.md'),Path('r16/ajuster.py')):
        frozen=subprocess.check_output(['git','show',f'{protocol}:vision-lab/{name}'])
        if frozen!=name.read_bytes():
            raise ValueError(f'Révision de protocole différente pour {name}')
    data=json.loads(inputs.read_bytes())
    index=json.loads(Path('r15/caracteristiques/index.json').read_bytes())
    names=data['colonnes']
    if data['checkpoint']!='ordinary-9301' or names!=index['colonnes_entree'] or len(names)!=8:
        raise ValueError('Matrice non autorisée')
    e=data['exemples']
    x=np.asarray([r['x'] for r in e],dtype=np.float64)
    y=np.array([r['y'] for r in e])
    w=np.array([r['poids'] for r in e])
    medians={}
    for name in ('largeur_mediane_px','largeur_mad_px'):
        j=names.index(name)
        medians[name]=float(np.nanmedian(x[:,j]))
        x[np.isnan(x[:,j]),j]=medians[name]
    if not np.isfinite(x).all() or not np.isclose(w.sum(),len(e)):
        raise ValueError('Préparation train incohérente')
    scaler=StandardScaler().fit(x,sample_weight=w)
    z=scaler.transform(x)
    model=LogisticRegression(C=1.,penalty='l2',solver='lbfgs',tol=1e-8,max_iter=200,
                             fit_intercept=True,random_state=1401)
    if output.exists():
        raise FileExistsError(output)
    with warnings.catch_warnings(record=True) as log:
        warnings.simplefilter('always',ConvergenceWarning)
        model.fit(z,y,sample_weight=w)
    result=dict(protocole_sha=protocol,exemples_sha256=sha256(inputs.read_bytes()).hexdigest(),
        checkpoint=data['checkpoint'],classes=model.classes_.tolist(),colonnes=names,
        imputation_medianes=medians,moyennes=scaler.mean_.tolist(),variances=scaler.var_.tolist(),
        echelles=scaler.scale_.tolist(),intercept=model.intercept_.item(),coefficients=model.coef_[0].tolist(),
        N=len(e),masse=float(w.sum()),iterations=int(model.n_iter_[0]),limite_iterations=200,C=1.,
        graine=1401,seuil=.5,tolerance=1e-8,solveur='lbfgs',nombre_ajustements=1,
        avertissements=[str(v.message) for v in log],numpy=np.__version__,scipy=scipy.__version__,sklearn=sklearn.__version__)
    with output.open('x') as f:
        json.dump(result,f,ensure_ascii=False,allow_nan=False,indent=2)
        f.write('\n')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('protocole_sha')
    parser.add_argument('sortie',type=Path)
    args=parser.parse_args()
    fit(args.protocole_sha,args.sortie)

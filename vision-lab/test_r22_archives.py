"""Vérification indépendante des pièces R22 après publication des trois trajectoires."""
import math
from pathlib import Path
import tempfile
import unittest

from r19.donnees import SEEDS, fingerprint, read
from r21.plan import STEPS
from r22.mesures import ARMS, replay
from r22.presenter import figures, tables

ROOT=Path('r22')


class ArchivesR22(unittest.TestCase):
    def assert_distance(self, computed, reference, key):
        a,b=computed[key],reference[key]
        if a==b:
            return 0
        self.assertIsInstance(a,float)
        self.assertIsInstance(b,float)
        # La norme euclidienne peut varier au dernier bit selon le noyau CPU.
        self.assertLessEqual(abs(a-b),2*math.ulp(b),(key,a,b))
        self.assertEqual(a<=10,b<=10,(key,a,b))
        computed[key]=b
        return 1

    def assert_raw_photo(self, computed, reference):
        changes=0
        for name,cells in computed['paires_par_identite'].items():
            for a,b in zip(cells,reference['paires_par_identite'][name],strict=True):
                changes+=self.assert_distance(a,b,'erreur_entree_px704')
        for field in ('couverture','au_seuil'):
            for a,b in zip(computed[field]['paires'],reference[field]['paires'],strict=True):
                changes+=self.assert_distance(a,b,'distance_px')
        self.assertEqual(computed,reference)
        return changes

    def test_rejeu_des_sorties_publiques_et_des_seuils_exacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)
            replay(out,ROOT/'archives/dice')
            tables(out)
            figures(out)
            outputs=[p for p in out.rglob('*') if p.is_file()]
            changed=0
            for p in outputs:
                published=ROOT/p.relative_to(out)
                if p.parent.name=='mesures' and p.name.startswith('dice-'):
                    computed,reference=read(p),read(published)
                    self.assertEqual(computed.keys(),reference.keys())
                    for k,r in computed.items():
                        changed+=self.assert_raw_photo(r,reference[k])
                else:
                    self.assertEqual(p.read_bytes(),published.read_bytes(),str(p))
            print(f'Rejeu R22 : {changed} distances dans la tolérance de deux ULP. Autres valeurs exactes.')
            self.assertEqual({p.suffix for p in outputs},{'.json','.md','.svg'})

    def test_tolerance_de_distance_ne_permet_ni_franchissement_ni_derive(self):
        a=2.
        plus=math.nextafter(a,math.inf)
        self.assertEqual(self.assert_distance({'d':plus},{'d':a},'d'),1)
        for reference,computed in [(10.,math.nextafter(10.,math.inf)),(a,a+3*math.ulp(a))]:
            with self.assertRaises(AssertionError):
                self.assert_distance({'d':computed},{'d':reference},'d')

    def test_gel_ressources_lectures_et_affectations_de_decision(self):
        config=read(ROOT/'configuration.json')
        for name,digest in config['empreintes_gel'].items():
            self.assertEqual(fingerprint(Path(name)),digest,name)
        seen=set()
        winning=[]
        contrasts=read(ROOT/'mesures/contrastes.json')
        for seed in SEEDS:
            folder=ROOT/'archives/dice'/str(seed)
            ledger=read(folder/'execution.json')
            self.assertEqual(ledger['etat_initial'],config['etats_initiaux_par_graine'][str(seed)])
            self.assertEqual(ledger['nombre_pas'],768)
            for step in STEPS:
                c=ledger['controles'][str(step)]
                self.assertEqual(len(set(c['photos_evaluees'])),9)
                seen.update((seed,step,k) for k in c['photos_evaluees'])
            end={}
            for arm in ARMS:
                path=(Path(f'r21/mesures/temoin-{seed}-768.json') if arm=='temoin'
                      else ROOT/f'mesures/dice-{seed}-768.json')
                rows=read(path)
                if arm=='temoin':
                    rows=rows['par_photo']
                triplets=set()
                entries=set()
                for k,r in rows.items():
                    chosen=[(n,i['conjoint_q']) for n,i in r['identites'].items() if i['conjoint_q'] is not None]
                    self.assertEqual(len({q for _,q in chosen}),len(chosen))
                    for n,q in chosen:
                        self.assertTrue(r['paires_par_identite'][n][q]['admissible_conjoint'])
                        triplets.add((k,n))
                    entries.update((k,i['instance']) for i in r['couverture']['paires'])
                end[arm]=triplets,entries
            old,old_entries=end['temoin']
            new,new_entries=end['dice']
            sources={p['photo']:p['provenance'] for p in config['photos']}
            gains={sources[k] for k,_ in new-old if sources[k]!='Crigges'}
            passed=len(gains)>=2 and old<=new and old_entries<=new_entries
            self.assertEqual(passed,contrasts[str(seed)]['768']['conforme'])
            if passed:
                winning.append(seed)
        self.assertEqual(len(seen),54)
        self.assertEqual(winning,read(ROOT/'decision.json')['graines_conformes'])
        self.assertEqual(len(winning)>=2,read(ROOT/'decision.json')['critere_confirme'])

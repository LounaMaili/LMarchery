"""Appliquer les paramètres publiés, sans scikit-learn ni apprentissage."""
from statistics import median
from time import process_time_ns

import numpy as np


class Verificateur:
    def __init__(self, modele):
        self.colonnes = tuple(modele['colonnes'])
        self.medianes = np.array([modele['imputation_medianes'].get(k, np.nan) for k in self.colonnes])
        self.moyennes = np.asarray(modele['moyennes'])
        self.echelles = np.asarray(modele['echelles'])
        self.beta = np.asarray(modele['coefficients'])
        self.intercept = modele['intercept']
        self.seuil = modele['seuil']

    def matrice(self, lignes):
        x = np.array([[r[k] if r[k] != '' else np.nan for k in self.colonnes] for r in lignes],
                     dtype=np.float64).reshape(-1, len(self.colonnes))
        if not np.isfinite(np.where(np.isnan(x), self.medianes, x)).all():
            raise ValueError('Caractéristique manquante hors des largeurs prévues')
        return x

    def appliquer(self, x):
        z = (np.where(np.isnan(x), self.medianes, x) - self.moyennes) / self.echelles
        logit = z @ self.beta + self.intercept
        prob = 1 / (1 + np.exp(-np.clip(logit, -700, 700)))
        return prob, prob >= self.seuil

    def chronometrer(self, x):
        for _ in range(5):
            self.appliquer(x)
        temps = []
        for _ in range(101):
            debut = process_time_ns()
            self.appliquer(x)
            temps.append(process_time_ns() - debut)
        return int(median(temps))

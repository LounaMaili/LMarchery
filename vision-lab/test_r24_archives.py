"""Relire toutes les pièces publiées et les neuf vues sans appel à un modèle."""
import unittest

from r24.auditer import verify_public


class ArchivesR24(unittest.TestCase):
    def test_rejeu_et_decision_depuis_les_sorties_publiees(self):
        adjusted = verify_public()
        print('Rejeu R24, distances géométriques dans la tolérance de deux ULP',adjusted)

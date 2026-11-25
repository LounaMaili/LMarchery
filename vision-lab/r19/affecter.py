"""Affectation rectangulaire de coût minimal, sans imposer SciPy au laboratoire."""
import numpy as np


def minimum(cost):
    """Une colonne distincte par ligne. Parcours croissants, première colonne en cas d'égalité."""
    a = np.asarray(cost, dtype=float)
    n, m = a.shape
    if n > m or not np.isfinite(a).all():
        raise ValueError('Matrice rectangulaire finie requise')
    u, v = np.zeros(n+1), np.zeros(m+1)
    owner = np.zeros(m+1, dtype=int)
    previous = np.zeros(m+1, dtype=int)
    for row in range(1, n+1):
        owner[0] = row
        best = np.full(m+1, np.inf)
        used = np.zeros(m+1, dtype=bool)
        col = 0
        while owner[col]:
            used[col] = True
            i = owner[col]
            free = np.flatnonzero(~used)
            reduced = a[i-1, free-1]-u[i]-v[free]
            improved = reduced < best[free]
            selected = free[improved]
            best[selected] = reduced[improved]
            previous[selected] = col
            col = free[np.argmin(best[free])]
            delta = best[col]
            u[owner[used]] += delta
            v[used] -= delta
            best[free] -= delta
        while col:
            prev = previous[col]
            owner[col] = owner[prev]
            col = prev
    result = np.empty(n, dtype=int)
    for c in np.flatnonzero(owner[1:]):
        result[owner[c+1]-1] = c
    return result


def pairs(valid, distance):
    """Cardinalité maximale puis distance totale minimale, distances valides dans [0,1]."""
    valid = np.asarray(valid, dtype=bool)
    n, m = valid.shape
    if not n or not m or not valid.any():
        return []
    # Retirer les colonnes sans arête évite les très grands tableaux des courbes B.
    cols = np.flatnonzero(valid.any(axis=0))
    penalty = n+1.
    costs = np.where(valid[:,cols], distance[:,cols], 3*penalty)
    assigned = minimum(np.column_stack((costs, np.full((n,n), penalty))))
    return [(i,int(cols[c])) for i,c in enumerate(assigned) if c<len(cols)]

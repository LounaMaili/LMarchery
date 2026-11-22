"""Draw the fixed-step whole-photo curves from published measurements."""
import argparse
import json
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.ticker import MaxNLocator


def draw(source, destination):
    runs = json.loads(source.read_text())["runs"]
    figure, axes = plt.subplots(2, 2, figsize=(10, 6.5), sharex=True)
    for column, threshold in enumerate(("at050", "at090")):
        for name, color, marker in (("A", "#165783", "o"), ("B", "#bf5923", "s"), ("C", "#27775f", "^")):
            curve = runs[name + "-8301"]["curve"]
            x = [c["step"] for c in curve]
            for ax, values in zip(axes[:, column], (
                [c[threshold]["all"]["matched"] for c in curve],
                [c[threshold]["all"]["unmatched"] / c[threshold]["all"]["photos"] for c in curve],
            )):
                ax.plot(x, values, marker=marker, color=color, label=name, linewidth=1.8)
                ax.grid(axis="y", color="#d9dfe1", linewidth=.65)
                ax.set_axisbelow(True)
                ax.spines[["top", "right"]].set_visible(False)
        axes[0, column].set_title(("Seuil 0,50", "Seuil 0,90")[column])
        axes[0, column].set_ylim(-.5, 15.8)
        axes[0, column].yaxis.set_major_locator(MaxNLocator(integer=True))
        axes[1, column].set_ylim(bottom=-.08)
        axes[1, column].set_xlabel("Mises à jour")
        axes[1, column].set_xticks(x)
    axes[0, 0].set_ylabel("Contacts appariés sur 15")
    axes[1, 0].set_ylabel("Propositions non appariées / photo")
    figure.suptitle("Mémorisation sur 12 photos du même support", fontsize=15)
    handles, _ = axes[0, 0].get_legend_handles_labels()
    figure.legend(handles, ("A · partielle", "B · complétée", "C · complétée + supports"),
                  loc="lower center", ncol=3, frameon=False)
    figure.tight_layout(rect=(0, .06, 1, .96))
    figure.savefig(destination, dpi=160)
    plt.close(figure)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path)
    p.add_argument("destination", type=Path)
    args = p.parse_args()
    draw(args.source, args.destination)

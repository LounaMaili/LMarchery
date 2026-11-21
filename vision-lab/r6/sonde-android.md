# Vérification Android virtuelle

Le programme [android_probe.c](android_probe.c) utilise l’API C du runtime Android officiel. Il prend un ONNX et une suite de fenêtres RGB NCHW float32 little-endian et écrit les logits dans le même format. Les fenêtres de développement du test de parité sont synthétiques. Le dossier de sortie reste sous `artifacts/`.

Depuis `vision-lab/`, avec la distribution NDK 28.0.13004108 du SDK Android et le même [AAR 1.23.2](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.23.2/) que dans le relevé des empreintes, extraire les en-têtes et la bibliothèque x86_64 dans `artifacts/android/`. Les commandes suivantes recompilent la sonde du laboratoire.

```bash
NDK_TOOLS="$ANDROID_HOME/ndk/28.0.13004108/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$NDK_TOOLS/x86_64-linux-android26-clang" -std=c11 -O2 -Wall -Werror \
  -Iartifacts/android/headers r6/android_probe.c -Lartifacts/android/jni/x86_64 \
  -lonnxruntime -o artifacts/android/probe
artifacts/ort-env/bin/python -c "import numpy as np; np.load('artifacts/r6-export/dev.npy').astype('<f4').tofile('artifacts/android/windows.f32')"
```

Sur une instance virtuelle identifiée par son numéro ADB `emulator-…`, copier la sonde, la bibliothèque, les deux graphes et le fichier de fenêtres dans le même répertoire temporaire de l’émulateur. Y lancer `LD_LIBRARY_PATH=. ./probe float.onnx windows.f32 float.f32` puis la variante INT8. Une exécution réussie doit produire 24 tenseurs de 3 × 160 × 192 valeurs par variante. Comparer les probabilités, les points et les décisions de silence avec les fichiers logits hôte de la même variante. La compilation seule a été vérifiée sur ce poste, les AVD API 35 et 36 s’étant arrêtés avant ADB.

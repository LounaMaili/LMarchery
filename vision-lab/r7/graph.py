"""Pre-training float graph feasibility on a deterministic artificial input."""
import argparse
from pathlib import Path
import os
import subprocess

import numpy as np
import torch

from lab_data import fingerprint, write_json
from r7.model import ContactNet


def check(folder,weights,runtime):
    folder.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    torch.manual_seed(7103)
    model=ContactNet(weights).eval()
    probe=torch.randint(0,256,(1,3,320,320),dtype=torch.uint8)
    graph=folder/"feasibility.onnx"
    torch.onnx.export(model,probe,graph,input_names=["rgb"],output_names=["logits"],
                      opset_version=18,dynamo=True,external_data=False)
    np.save(folder/"probe.npy",probe.numpy())
    script=("import numpy as np, onnxruntime as ort; "
            "o=ort.SessionOptions(); o.intra_op_num_threads=2; "
            "s=ort.InferenceSession('feasibility.onnx',o,providers=['CPUExecutionProvider']); "
            "np.save('ort.npy',s.run(None,{'rgb':np.load('probe.npy')})[0])")
    subprocess.run([str(runtime.absolute()),"-c",script],cwd=folder,
                   env={k:v for k,v in os.environ.items() if k!="PYTHONPATH"},check=True)
    with torch.inference_mode():
        diff=model(probe).sigmoid()-torch.from_numpy(np.load(folder/"ort.npy")).sigmoid()
    error=diff.abs().max().item()
    if error > .001:
        raise ValueError(f"Unexpected float graph error {error}")
    write_json(folder/"graph.json",dict(bytes=graph.stat().st_size,sha256=fingerprint(graph),
               max_probability_error=error,opset=18,inputs=1,trained_head=False))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for n in ("folder","weights","runtime"):
        p.add_argument(n,type=Path)
    a=p.parse_args()
    check(a.folder,a.weights,a.runtime)

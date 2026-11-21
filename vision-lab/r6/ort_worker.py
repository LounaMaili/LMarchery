"""Independent ONNX Runtime CPU worker. Input arrays come from synthetic train/dev."""
import json
from pathlib import Path
import resource
import sys
import time

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static


class Calibration(CalibrationDataReader):
    def __init__(self, frames):
        self.iterator = iter(frames)

    def get_next(self):
        frame = next(self.iterator, None)
        return None if frame is None else {"rgb": frame[None]}


def run(root):
    fp, quantized = root / "float.onnx", root / "int8.onnx"
    quantize_static(str(fp), str(quantized), Calibration(np.load(root / "train.npy")),
                    quant_format=QuantFormat.QDQ, activation_type=QuantType.QInt8,
                    weight_type=QuantType.QInt8, per_channel=True, op_types_to_quantize=["Conv"])
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    frames = np.load(root / "dev.npy")
    report = {}
    for graph in (fp, quantized):
        start = time.perf_counter()
        session = ort.InferenceSession(str(graph), sess_options=options, providers=["CPUExecutionProvider"])
        load = time.perf_counter()-start
        outputs, times = [], []
        for frame in frames:
            start = time.perf_counter()
            outputs.append(session.run(None, {"rgb": frame[None]})[0])
            times.append((time.perf_counter()-start)*1000)
        np.save(root / (graph.stem+"-logits.npy"), np.concatenate(outputs))
        report[graph.stem] = dict(load_ms=load*1000, first_ms=times[0], warm_p95_ms=np.percentile(times[1:],95))
    report["ort"] = ort.__version__
    report["worker_peak_rss_kib"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    (root / "runtime.json").write_text(json.dumps(report, indent=2)+"\n")


if __name__ == "__main__":
    run(Path(sys.argv[1]))

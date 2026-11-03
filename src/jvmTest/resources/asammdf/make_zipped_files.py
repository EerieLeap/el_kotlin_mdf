import sys
import numpy as np
from asammdf import MDF, Signal

out = sys.argv[1]
t = np.arange(0, 2000, 1.0) * 0.01
strings = np.array([("hello %d" % (i % 7)).encode() for i in range(len(t))])
for name, kwargs in (("strings", {}), ("zipped", {"compression": 1}), ("transposed", {"compression": 2})):
    mdf = MDF(version="4.10")
    mdf.append([
        Signal(samples=np.sin(t), timestamps=t, name="sine", unit="V"),
        Signal(samples=(t * 100).astype(np.int32), timestamps=t, name="count"),
    ])
    if name == "strings":
        mdf.append([Signal(samples=strings, timestamps=t, name="text", encoding="utf-8")])
    mdf.save(f"{out}/{name}.mf4", overwrite=True, **kwargs)
    mdf.close()
print("ok")

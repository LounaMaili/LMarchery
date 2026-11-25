"""Décodage de la photo seule. Les indices de requête sont les identités de sortie locales."""
import cv2 as cv
import numpy as np

from r19.donnees import eval_size, work_to


def fragments(on):
    n, labels, boxes, _ = cv.connectedComponentsWithStats(on.astype(np.uint8), connectivity=8)
    segments = []
    for i in range(1,n):
        x,y,w,h,area = boxes[i]
        if area<3 or max(w,h)<3:
            continue
        yy,xx = np.where(labels[y:y+h,x:x+w]==i)
        coords = np.column_stack((xx+x,yy+y))
        center = coords.mean(0)
        values, directions = np.linalg.eigh(np.cov(coords.T))
        if values[-1] < 3*max(1.e-9,values[0]):
            continue
        direction = directions[:,-1]
        if direction[np.argmax(np.abs(direction))]<0:
            direction = -direction
        t = (coords-center)@direction
        span = t.max()-t.min()
        if span < 3:
            continue
        line = center+np.linspace(t.min(),t.max(),int(np.ceil(2*span))+1)[:,None]*direction
        pixels = np.floor(line+.5).astype(int)
        inside = ((pixels>=0)&(pixels<np.array(on.shape[1::-1]))).all(axis=1)
        pixels = pixels.clip(0,np.array(on.shape[1::-1])-1)
        inside &= labels[pixels[:,1],pixels[:,0]]==i
        switches = np.flatnonzero(np.diff(np.r_[False,inside,False]))
        for begin,end in switches.reshape(-1,2):
            if np.linalg.norm(line[end-1]-line[begin])>=2:
                segments.append([line[begin],line[end-1]])
    return segments


def decode(fields, presence, input_wh, source_wh):
    grid = np.array(input_wh)//2
    view = eval_size(source_wh)
    masks = (fields[:,0]>=0).reshape(len(fields),grid[1],grid[0])
    axes = (fields[:,1]>=0).reshape(masks.shape) & masks
    prob = fields[:,2].softmax(1).detach().cpu().numpy()
    obj = presence.sigmoid().detach().cpu().numpy()
    packed = np.zeros(masks.shape[1:],np.uint16)
    records = []
    for slot in range(len(fields)):
        mask = masks[slot].detach().cpu().numpy()
        packed |= mask.astype(np.uint16)<<slot
        peak = int(prob[slot].argmax())
        xy = [peak%grid[0],peak//grid[0]]
        lines = fragments(axes[slot].detach().cpu().numpy())
        segments = work_to(lines,grid,view).tolist() if lines else []
        records.append(dict(id=slot, xy=work_to(xy,grid,view).tolist(), score=float(obj[slot])*float(prob[slot,peak]),
            presence=float(obj[slot]), pic_spatial=float(prob[slot,peak]), segments=segments, cellules_masque=int(mask.sum())))
    return dict(grille=grid.tolist(), repere_704=view.tolist(), sorties=records), packed

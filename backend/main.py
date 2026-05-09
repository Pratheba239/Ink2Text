from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from io import BytesIO
from PIL import Image, ImageOps
import uvicorn
from ml_model import HandwritingRecognizer

app = FastAPI(title="Ink2Text API")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

recognizer = None

@app.on_event("startup")
async def startup_event():
    global recognizer
    try:
        recognizer = HandwritingRecognizer("microsoft/trocr-base-handwritten")
    except Exception as e:
        print(f"Failed to load model: {e}")

@app.get("/")
def read_root():
    return {"status": "ok", "message": "Ink2Text API is running"}

@app.post("/api/recognize")
async def recognize_text(image: UploadFile = File(...)):
    global recognizer
    if recognizer is None:
        raise HTTPException(status_code=503, detail="Model not loaded yet")
    
    if not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File provided is not an image")
        
    try:
        contents = await image.read()
        pil_image = Image.open(BytesIO(contents))
        pil_image = ImageOps.exif_transpose(pil_image)
        
        text = recognizer.recognize(pil_image)
        return {"text": text}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error processing image: {str(e)}")

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)

from transformers import TrOCRProcessor, VisionEncoderDecoderModel
from PIL import Image
import cv2
import numpy as np

class HandwritingRecognizer:
    def __init__(self, model_name="microsoft/trocr-base-handwritten"):
        print(f"Loading model {model_name}...")
        self.processor = TrOCRProcessor.from_pretrained(model_name)
        self.model = VisionEncoderDecoderModel.from_pretrained(model_name)
        print("Model loaded successfully.")

    def segment_lines(self, pil_image: Image.Image):
        import os
        debug_dir = "debug_crops"
        os.makedirs(debug_dir, exist_ok=True)
        
        # Convert PIL Image to OpenCV format
        opencv_image = cv2.cvtColor(np.array(pil_image), cv2.COLOR_RGB2BGR)
        gray = cv2.cvtColor(opencv_image, cv2.COLOR_BGR2GRAY)
        
        # Blur to remove noise
        blur = cv2.GaussianBlur(gray, (7,7), 0)
        
        # Adaptive thresholding to binarize (invert so text is white on black)
        # Using a larger block size to handle uneven lighting/shadows
        thresh = cv2.adaptiveThreshold(blur, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 51, 15)
        
        cv2.imwrite(os.path.join(debug_dir, "1_thresh.jpg"), thresh)
        
        # Dilation to merge characters into lines
        # Using a purely horizontal kernel (100 wide, 1 tall) to prevent vertical merging of lines
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (100, 1))
        dilate = cv2.dilate(thresh, kernel, iterations=2)
        
        cv2.imwrite(os.path.join(debug_dir, "2_dilate.jpg"), dilate)
        
        # Find contours
        contours, _ = cv2.findContours(dilate, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        
        # Filter small contours and sort by Y coordinate (top to bottom)
        line_boxes = []
        for c in contours:
            x, y, w, h = cv2.boundingRect(c)
            if w > 50 and h > 15: # Ignore small noise
                line_boxes.append((x, y, w, h))
                
        line_boxes.sort(key=lambda b: b[1]) # Sort by Y
        
        # Draw bounding boxes on original image for debugging
        debug_img = opencv_image.copy()
        for (x, y, w, h) in line_boxes:
            cv2.rectangle(debug_img, (x, y), (x+w, y+h), (0, 255, 0), 2)
        cv2.imwrite(os.path.join(debug_dir, "3_boxes.jpg"), debug_img)
        
        line_images = []
        for i, (x, y, w, h) in enumerate(line_boxes):
            # Add some padding to the crop
            pad_y = 15
            pad_x = 10
            y1 = max(0, y - pad_y)
            y2 = min(pil_image.height, y + h + pad_y)
            x1 = max(0, x - pad_x)
            x2 = min(pil_image.width, x + w + pad_x)
            
            # Crop the original PIL image
            crop = pil_image.crop((x1, y1, x2, y2))
            crop.save(os.path.join(debug_dir, f"line_{i}.jpg"))
            line_images.append(crop)
            
        return line_images

    def recognize(self, image: Image.Image) -> str:
        # The TrOCR model expects RGB images
        if image.mode != "RGB":
            image = image.convert("RGB")
            
        line_images = self.segment_lines(image)
        
        if not line_images:
            # Fallback to full image if no lines detected
            line_images = [image]
            
        recognized_lines = []
        for line_img in line_images:
            pixel_values = self.processor(line_img, return_tensors="pt").pixel_values
            generated_ids = self.model.generate(pixel_values, max_new_tokens=50)
            text = self.processor.batch_decode(generated_ids, skip_special_tokens=True)[0]
            if text.strip():
                recognized_lines.append(text)
                
        return "\n".join(recognized_lines)

# Initialize a global instance (in a real app, you might want to lazy-load this or manage its lifecycle)
# We'll initialize it in main.py to control startup.

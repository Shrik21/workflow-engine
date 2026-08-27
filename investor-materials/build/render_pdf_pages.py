import pypdfium2 as pdfium
from pathlib import Path

pdf_path = Path(r"C:\Users\ASUS\OneDrive\Desktop\New folder\sitepilot\investor-materials\build\doc-render\OrchPilot-Investor-Strategy-and-Product-Brief.pdf")
out = pdf_path.parent
pdf = pdfium.PdfDocument(str(pdf_path))
for i in range(len(pdf)):
    page = pdf[i]
    bitmap = page.render(scale=1.5)
    bitmap.to_pil().save(out / f"page-{i+1}.png")
print(len(pdf))

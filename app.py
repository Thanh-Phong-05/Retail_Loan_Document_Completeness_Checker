from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List
import uvicorn
from datetime import datetime
import os

from statement_contract_checker import StatementContractChecker
from classifier import process_documents 

app = FastAPI(title="Retail Loan AI Analyzer API")
statement_checker = StatementContractChecker()

class FileData(BaseModel):
    fileId: str
    fileName: str
    filePath: str

class AnalyzeRequest(BaseModel):
    applicationId: str
    dossierVersion: int
    files: List[FileData]

@app.post("/api/ai/analyze")
async def analyze_dossier(request: AnalyzeRequest):
    if not request.files:
        raise HTTPException(status_code=400, detail="Không có file nào được cung cấp")

    for file in request.files:
        if not os.path.exists(file.filePath):
            raise HTTPException(status_code=404, detail=f"Không tìm thấy file: {file.filePath}")

    pages_data = process_documents(request.files)
    if not pages_data:
         raise HTTPException(status_code=400, detail="Không đọc được nội dung file")

    grouped_pages = {}
    for p in pages_data:
        doc_class = p.get("documentClass")
        if doc_class not in grouped_pages:
            grouped_pages[doc_class] = []
        grouped_pages[doc_class].append(p)

    results = []

    if "BANK_STATEMENT" in grouped_pages:
        res = statement_checker.check_statement(grouped_pages["BANK_STATEMENT"])
        results.append({"documentType": "BANK_STATEMENT", **res})

    for contract_type in ["LABOR_CONTRACT", "LOAN_APPLICATION"]:
        if contract_type in grouped_pages:
            res = statement_checker.check_contract(grouped_pages[contract_type])
            results.append({"documentType": contract_type, **res})

    # Mock CCCD (Chờ Java link với Tín)
    if "NATIONAL_ID" in grouped_pages:
        results.append({
            "documentType": "NATIONAL_ID",
            "status": "VALID", 
            "pageAnchor": "page_cccd",
            "details": "Mock data (Java sẽ link với kết quả của Tín)"
        })

    return {
        "success": True,
        "applicationId": request.applicationId,
        "analyzedAt": datetime.utcnow().isoformat() + "Z",
        "data": results
    }

if __name__ == "__main__":
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)
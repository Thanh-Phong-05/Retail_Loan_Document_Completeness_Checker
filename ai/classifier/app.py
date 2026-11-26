from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List
import uvicorn
from datetime import datetime
import os

from statement_contract_checker import StatementContractChecker
from classifier import process_documents 

try:
    from extractor.id_card_checker import IDCardChecker
    id_checker = IDCardChecker()
except ImportError:
    try:
        from ai.extractor.id_card_checker import IDCardChecker
        id_checker = IDCardChecker()
    except ImportError:
        id_checker = None

app = FastAPI(title="Retail Loan AI Analyzer API")
statement_checker = StatementContractChecker()

class FileData(BaseModel):
    fileId: str
    fileName: str
    storagePath: str 

class AnalyzeRequest(BaseModel):
    applicationId: str
    dossierVersion: int
    files: List[FileData]

@app.post("/api/ai/analyze")
async def analyze_dossier(request: AnalyzeRequest):
    if not request.files:
        raise HTTPException(status_code=400, detail="Không có file nào được cung cấp")

    for file in request.files:
        if not os.path.exists(file.storagePath):
            raise HTTPException(status_code=404, detail=f"Không tìm thấy file: {file.storagePath}")

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
        results.append({
            "documentClass": "BANK_STATEMENT",
            "status": res["status"],
            "pages": grouped_pages["BANK_STATEMENT"],
            "details": res.get("details", ""),
            "missingMonths": res.get("missing_months", [])
        })

    for contract_type in ["LABOR_CONTRACT", "LOAN_APPLICATION"]:
        if contract_type in grouped_pages:
            res = statement_checker.check_contract(grouped_pages[contract_type])
            results.append({
                "documentClass": contract_type,
                "status": res["status"],
                "pages": grouped_pages[contract_type],
                "details": res.get("details", "")
            })

    if "NATIONAL_ID" in grouped_pages:
        if id_checker:
            cccd_res = id_checker.check_pages(grouped_pages["NATIONAL_ID"])
            results.append({
                "documentClass": "NATIONAL_ID",
                "status": cccd_res.status,
                "pages": grouped_pages["NATIONAL_ID"],
                "details": getattr(cccd_res, 'details', "Đã check CCCD")
            })
        else:
            results.append({
                "documentClass": "NATIONAL_ID",
                "status": "UNKNOWN",
                "pages": grouped_pages["NATIONAL_ID"],
                "details": "Chưa tìm thấy module id_card_checker của Tín"
            })

    return {
        "applicationId": request.applicationId,
        "dossierVersion": request.dossierVersion,
        "results": results
    }

if __name__ == "__main__":
    uvicorn.run("app:app", host="0.0.0.0", port=8000, reload=True)
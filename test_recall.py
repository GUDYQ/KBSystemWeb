import json
import logging
from typing import List, Dict

# Assuming you might use requests to call your API or directly connect to PostgreSQL
import requests

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# You can modify these configurations according to your environment
API_URL = "http://localhost:8080/api/test/ai/search" # Example API endpoint for your vector search

def search_documents(query: str, top_k: int = 5) -> List[Dict]:
    """
    Search your system for relevant documents.
    """
    payload = {
        "query": query
    }

    try:
        response = requests.post(API_URL, json=payload)
        return response.json()
    except Exception as e:
        logging.error(f"Search failed: {e}")
        return []

def test_recall_rate(test_cases: List[Dict], top_k: int = 5) -> float:
    """
    Run tests and calculate Recall@K.
    """
    hits = 0
    total = len(test_cases)

    if total == 0:
        return 0.0

    for i, test_case in enumerate(test_cases):
        question = test_case["question"]
        expected_id = test_case["expected_doc_id"]

        results = search_documents(question, top_k=top_k)

        # Retrieve file name from metadata
        retrieved_files = []
        for res in results:
            # Unwrap the "data" field from the ResponseVO structure
            data = res.get("data", {}) if isinstance(res, dict) else {}
            meta = data.get("metadata", {})
            file_name = str(meta.get("file_name", meta.get("source", meta)))
            retrieved_files.append(file_name)

        # Check if the expected document is in the retrieved results
        hit = any(expected_id in f for f in retrieved_files)

        if hit:
            hits += 1
            logging.info(f"[{i+1}/{total}] HIT! Question: {question}")
        else:
            logging.info(f"[{i+1}/{total}] MISS. Question: {question} | Expected: {expected_id} | Got: {retrieved_files}")

    recall = hits / total
    logging.info(f"--- Evaluation Finished ---")
    logging.info(f"Total Test Cases: {total}")
    logging.info(f"Hits @ {top_k}: {hits}")
    logging.info(f"Recall @ {top_k}: {recall:.2%}")

    return recall

if __name__ == "__main__":
    # Example test dataset
    dataset = [
        {"question": "如果单身太久感到焦虑怎么办？", "expected_doc_id": "单身篇"},
        {"question": "结婚后财政大权应该交给谁？", "expected_doc_id": "已婚篇"},
        {"question": "异地恋怎么维持感情？", "expected_doc_id": "恋爱篇"}
    ]

    # Calculate Recall@Top 3
    test_recall_rate(dataset, top_k=3)

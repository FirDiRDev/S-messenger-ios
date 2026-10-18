# -*- coding: utf-8 -*-
"""
Создать ключи подписи чеков (один раз).

Сервер подписывает каждый чек о покупке закрытым ключом (ECDSA P-256), а приложение
проверяет подпись открытым ключом (publicKey в блоке coinShop файла update.json). Подделать
чек без закрытого ключа нельзя - даже если кто-то знает адрес сервера.

Запуск:  python keys.py
Закрытый ключ сохранится в receipt_private.pem (никому не отдавайте и не выкладывайте
в GitHub!), открытый - будет напечатан: вставьте его в приложение.
"""
import base64
import os

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

import config

if os.path.exists(config.PRIVATE_KEY_PATH):
    print("Ключ уже есть:", config.PRIVATE_KEY_PATH, "- не перезаписываю (иначе старые чеки станут недействительны).")
    with open(config.PRIVATE_KEY_PATH, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
else:
    key = ec.generate_private_key(ec.SECP256R1())
    with open(config.PRIVATE_KEY_PATH, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    os.chmod(config.PRIVATE_KEY_PATH, 0o600)
    print("Создан закрытый ключ:", config.PRIVATE_KEY_PATH)

pub = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
print()
print("Открытый ключ для приложения (update.json → coinShop → publicKey):")
print(base64.b64encode(pub).decode())

#!/bin/bash
# Генерирует пару RSA-ключей для подписи JWT. Каталог keys/ в .gitignore.
set -e
cd "$(dirname "$0")/.."
mkdir -p keys
if [ -f keys/private.pem ]; then
  echo "keys/private.pem уже существует, ничего не делаю" >&2
  exit 1
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl rsa -pubout -in keys/private.pem -out keys/public.pem
echo "Готово: keys/private.pem (только для account-service), keys/public.pem (для остальных)"

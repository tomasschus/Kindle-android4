import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadBucketCommand,
  CreateBucketCommand,
  S3Client,
} from "@aws-sdk/client-s3";
import { Upload } from "@aws-sdk/lib-storage";
import type { Readable } from "node:stream";

const BUCKET = process.env.GARAGE_BUCKET ?? "kindle-pdfs";

let client: S3Client | undefined;

function s3(): S3Client {
  if (client) return client;
  client = new S3Client({
    endpoint: process.env.GARAGE_ENDPOINT,
    region: process.env.GARAGE_REGION ?? "garage",
    forcePathStyle: true,
    credentials: {
      accessKeyId: process.env.GARAGE_ACCESS_KEY_ID ?? "",
      secretAccessKey: process.env.GARAGE_SECRET_ACCESS_KEY ?? "",
    },
  });
  return client;
}

/** Creates the configured bucket if it doesn't already exist. Safe to call on every boot. */
export async function ensureBucket(): Promise<void> {
  try {
    await s3().send(new HeadBucketCommand({ Bucket: BUCKET }));
  } catch {
    await s3().send(new CreateBucketCommand({ Bucket: BUCKET }));
  }
}

export async function putObject(
  key: string,
  body: Readable,
  contentType: string
): Promise<void> {
  const upload = new Upload({
    client: s3(),
    params: { Bucket: BUCKET, Key: key, Body: body, ContentType: contentType },
  });
  await upload.done();
}

export async function deleteObject(key: string): Promise<void> {
  await s3().send(new DeleteObjectCommand({ Bucket: BUCKET, Key: key }));
}

/**
 * Streams an object back, optionally honoring an HTTP Range header so large
 * PDFs can be resumed/partially fetched over a slow tablet connection.
 */
export async function getObjectStream(key: string, range?: string) {
  const result = await s3().send(
    new GetObjectCommand({ Bucket: BUCKET, Key: key, Range: range })
  );
  return result;
}
